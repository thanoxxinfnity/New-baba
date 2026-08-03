package com.trellis.studio.network

import com.trellis.studio.data.model.ChatTurn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
                code
            }
    }

    private companion object {
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
