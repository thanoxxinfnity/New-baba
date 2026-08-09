package com.trellis.studio.network

import com.trellis.studio.data.model.ChatTurn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Turns a plain-language game idea plus a set of 3D models into one Godot 4
 * GDScript file that builds the whole game in code.
 *
 * The heavy lifting is a careful system prompt: the model is told exactly which
 * `.glb` files exist, the Godot 4.3 API to stay inside, and that everything —
 * environment, lighting, ground, camera, player, controls and game loop — has
 * to be created in `_ready`/`_physics_process`, so the project needs no
 * hand-authored scene beyond the root node.
 */
class GameDirector(private val nim: NimClient = NimClient()) {

    /** One 3D asset the game needs: an id, what it's for, and a text-to-3D prompt. */
    data class AssetSpec(val name: String, val role: String, val prompt: String)

    /**
     * Given only the game idea, decides which 3D models the game needs and writes
     * a text-to-3D prompt for each — so the user never has to pick or provide
     * models; VOID generates them. Capped small because each model is a separate
     * (slow) 3D generation.
     */
    suspend fun planAssets(
        apiKey: String,
        model: String,
        gameIdea: String,
        max: Int = 3,
    ): Result<List<AssetSpec>> = withContext(Dispatchers.IO) {
        val user = "GAME IDEA:\n${gameIdea.trim()}\n\n" +
            "List the 3D models this game needs (at most $max). Output ONLY a JSON array."
        nim.chat(
            apiKey = apiKey,
            model = model,
            turns = listOf(ChatTurn("system", ASSET_PROMPT), ChatTurn("user", user)),
            maxTokens = 900,
            temperature = 0.4,
        ).map { it.content.ifBlank { it.reasoning.orEmpty() } }
            .mapCatching { raw -> parseAssets(raw, max) }
    }

    private fun parseAssets(raw: String, max: Int): List<AssetSpec> {
        val start = raw.indexOf('[')
        val end = raw.lastIndexOf(']')
        require(start in 0 until end) { "The model didn't return an asset list." }
        val arr = Json { isLenient = true; ignoreUnknownKeys = true }
            .parseToJsonElement(raw.substring(start, end + 1)).jsonArray
        return arr.mapNotNull { el ->
            val o = el.jsonObject
            val name = o["name"]?.jsonPrimitive?.content?.trim().orEmpty()
            val prompt = o["prompt"]?.jsonPrimitive?.content?.trim().orEmpty()
            val role = o["role"]?.jsonPrimitive?.content?.trim().orEmpty().ifBlank { name }
            if (name.isBlank() || prompt.isBlank()) null else AssetSpec(name, role, prompt)
        }.take(max)
    }

    /**
     * @param modelLines one line per model, e.g. `- coin_1.glb : a gold coin`.
     */
    suspend fun write(
        apiKey: String,
        model: String,
        gameIdea: String,
        modelLines: List<String>,
    ): Result<String> = withContext(Dispatchers.IO) {
        val user = buildString {
            appendLine("GAME IDEA:")
            appendLine(gameIdea.trim().ifBlank { "A simple explorable 3D scene using the models." })
            appendLine()
            appendLine("MODELS in res://models/ (load with load(\"res://models/<name>.glb\")):")
            if (modelLines.isEmpty()) appendLine("- (none provided; build the game with primitives)")
            else modelLines.forEach { appendLine(it) }
            appendLine()
            append("Write main.gd now. Output ONLY the GDScript.")
        }

        nim.chat(
            apiKey = apiKey,
            model = model,
            turns = listOf(ChatTurn("system", SYSTEM_PROMPT), ChatTurn("user", user)),
            maxTokens = 6000,
            temperature = 0.3,
        ).map { it.content.ifBlank { it.reasoning.orEmpty() } }
            .mapCatching { code ->
                require(code.contains("func _ready") || code.contains("extends")) {
                    "The model didn't return usable game code. Try again or pick another agent model."
                }
                // A second pass that fixes the compile/runtime errors these models
                // still slip in — out-of-scope variables, wrong property names,
                // Godot-3 leftovers — the kind a phone can't catch without Godot.
                // Verified live: it turned two games that failed in real Godot 4.3
                // ("scale_factor not declared", "Invalid assignment 'background'")
                // into ones that load and run clean. Best-effort: if the review
                // call fails, keep the first draft (still deterministically repaired).
                review(apiKey, model, code).getOrDefault(code)
            }
    }

    /** One self-review round: hand the code back and ask for the errors fixed. */
    private suspend fun review(apiKey: String, model: String, code: String): Result<String> =
        nim.chat(
            apiKey = apiKey,
            model = model,
            turns = listOf(ChatTurn("system", REVIEW_PROMPT), ChatTurn("user", "Fix this main.gd:\n\n$code")),
            maxTokens = 8000,
            temperature = 0.1,
        ).map { it.content.ifBlank { it.reasoning.orEmpty() } }
            .mapCatching { fixed ->
                require(fixed.contains("func _ready") || fixed.contains("extends")) {
                    "review returned no usable code"
                }
                fixed
            }

    private companion object {
        val ASSET_PROMPT = """
            You are a game art director. Given a game idea, decide the few 3D
            models the game needs (the player character and the key props), and
            write a text-to-3D prompt for each.

            Output ONLY a JSON array, each item:
              {"name": "<short_id>", "role": "<what it is in the game>",
               "prompt": "<text-to-3D description>"}

            Rules:
            - name: a short lowercase id, letters/underscores only (e.g. "player",
              "coin", "tree", "enemy").
            - prompt: describe ONE single object on a plain background, vivid but
              compact, good for an image-to-3D generator. No scene, no multiple
              objects, no text. e.g. "a cute low-poly red race car, single object,
              plain background".
            - Keep it to the essentials (usually 2-3 models). The player character
              first. Do not include ground, sky, lights or the camera — those are
              built in code, not modelled.
            - Output ONLY the JSON array, nothing else.
        """.trimIndent()

        val REVIEW_PROMPT = """
            You are a Godot 4.3 GDScript compiler. You are given a main.gd. Find and
            fix EVERY error that would stop it loading or running in Godot 4.3,
            especially:
            - identifiers used out of the scope they were declared in (e.g. a var
              declared inside an `if`/`for` block but used after it) — hoist the
              declaration so it is in scope everywhere it is used.
            - wrong property names: Environment uses `background_mode` (NOT
              `background`); WorldEnvironment.environment; ProceduralSkyMaterial has
              NO sun_latitude/sun_longitude; CharacterBody3D uses `velocity` then
              `move_and_slide()` with no arguments.
            - Godot 3 API, invalid `:=` inference, get_window_size(), or invented
              input actions (only ui_left/right/up/down/accept exist).
            Return the COMPLETE corrected GDScript ONLY — no markdown fences, no
            commentary. Keep all working logic; change only what is needed to make
            it compile and run on the first Play.
        """.trimIndent()

        val SYSTEM_PROMPT = """
            You are a Godot 4.3 GDScript expert. You write ONE GDScript file
            (main.gd) that builds a complete, playable 3D game entirely in code.
            It is attached to a root Node3D named "Main". The project already
            contains the user's 3D models as .glb files under res://models/. Load
            one with load("res://models/<file>.glb"), which returns a PackedScene;
            call .instantiate() and add_child() it.

            In _ready() you MUST build everything, in this order:
            - Environment: a WorldEnvironment with a procedural sky
              (ProceduralSkyMaterial), ambient light from the sky, and soft glow,
              so it looks realistic.
            - Lighting: a DirectionalLight3D angled down with shadow_enabled = true.
            - Ground: a StaticBody3D with a CollisionShape3D (a large BoxShape3D or
              WorldBoundaryShape3D) and a visible MeshInstance3D (PlaneMesh with a
              StandardMaterial3D), so nothing falls through.
            - Camera3D positioned to see the action (a follow camera is good).
            - Instance the provided models and place them for the game.
            - Player: a CharacterBody3D with a CollisionShape3D, using one model as
              its visual child. Move it in _physics_process with gravity,
              move_and_slide(), Input.get_vector("ui_left","ui_right","ui_up",
              "ui_down") for keys, AND on-screen touch: add a CanvasLayer with a
              simple virtual joystick or drag-to-move so it is playable on a phone.
            - The game loop the user described (collecting, scoring, enemies, win
              or lose), with an on-screen Label in a CanvasLayer for score/status.

            HARD RULES:
            - Godot 4.3 API ONLY. Use CharacterBody3D.velocity, move_and_slide()
              (no arguments), Vector3, Input.get_vector, Area3D + body_entered for
              pickups, get_tree().create_timer. NEVER Godot 3 names (no
              KinematicBody, no "export var", no yield). Use @onready and signals
              with .connect(Callable).
            - Guard every model load: if ResourceLoader.exists(path).
            - Use only the built-in input actions ui_left/ui_right/ui_up/ui_down/
              ui_accept, which always exist — do not invent input actions.
            - Indent with TABS, consistently. The code must run on the first Play
              with no errors.
            - Output ONLY the GDScript code. No markdown fences, no commentary.

            COMMON MISTAKES THAT BREAK THE GAME — DO NOT MAKE THEM:
            - There is NO get_window_size() or OS.get_window_size(). For the screen
              size use: get_viewport().get_visible_rect().size
            - InputEvent.position is untyped on the base class, so `var p :=
              event.position` fails to compile. When reading touch/mouse input,
              cast first: `var t := event as InputEventScreenTouch` /
              `event as InputEventScreenDrag`, then use t.position; or write the
              untyped form `var p = event.position` (single `=`, no colon).
            - Only use `:=` when the right-hand side has a clear type. If unsure,
              use a plain `=` or an explicit type: `var v: Vector2 = ...`.
            - Connect signals the Godot 4 way: area.body_entered.connect(_on_hit).
            - CharacterBody3D moves with `velocity` then `move_and_slide()` — never
              pass arguments to move_and_slide().
            - ProceduralSkyMaterial has sky_top_color, sky_horizon_color,
              ground_bottom_color, ground_horizon_color, sun_angle_max. It has NO
              sun_latitude — never set it; the sun direction is the
              DirectionalLight3D's rotation.
        """.trimIndent()
    }
}
