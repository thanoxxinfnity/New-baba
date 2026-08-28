package com.trellis.studio.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Assembles a complete, runnable Godot 4 project from the user's 3D models and
 * one AI-written GDScript, then zips it so it can be opened in Godot on the
 * phone.
 *
 * The fragile, format-sensitive parts of a Godot project — `project.godot`, the
 * scene file, the input map — are templated here, byte for byte, so they are
 * always valid. The AI only ever writes `main.gd`, which is the part a language
 * model is actually good at. That split is what makes the output run on the
 * first Play instead of failing on a malformed scene resource.
 */
object GameForge {

    data class ModelInput(
        /** The generated .glb on disk. */
        val file: File,
        /** A human description, so the AI knows what each model is for. */
        val role: String,
    )

    data class Assembled(
        val zip: File,
        /** The res:// names the AI was told to use, in order. */
        val modelNames: List<String>,
    )

    /** Sanitises a name into a safe, unique `res://models/<x>.glb` file name. */
    fun modelName(role: String, index: Int): String {
        val base = role.lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .take(24)
            .ifBlank { "model" }
        return "${base}_$index"
    }

    /**
     * Writes the project to [outputDir] and zips it. [mainGd] is the AI's
     * GDScript; [models] are copied in under `models/` with the names given by
     * [modelName], which are the same names the AI was told to load.
     */
    suspend fun assemble(
        gameName: String,
        mainGd: String,
        models: List<ModelInput>,
        outputDir: File,
    ): Result<Assembled> = withContext(Dispatchers.IO) {
        runCatching {
            require(mainGd.isNotBlank()) { "No game code was generated." }
            outputDir.mkdirs()

            val safeName = gameName.ifBlank { "VOID Game" }
            val work = File(outputDir, "game_${System.currentTimeMillis()}").apply { mkdirs() }
            val modelsDir = File(work, "models").apply { mkdirs() }

            val names = models.mapIndexed { i, m ->
                val name = modelName(m.role, i + 1)
                m.file.copyTo(File(modelsDir, "$name.glb"), overwrite = true)
                name
            }

            File(work, "project.godot").writeText(projectGodot(safeName))
            File(work, "main.tscn").writeText(MAIN_TSCN)
            File(work, "icon.svg").writeText(ICON_SVG)
            // Strip any code fence, then repair the handful of Godot-4 mistakes
            // these models still slip in despite the prompt — the phone has no
            // Godot to catch them, so the fixes have to be guaranteed here.
            File(work, "main.gd").writeText(repair(stripFences(mainGd)))
            File(work, "README.txt").writeText(readme(safeName, names))

            val zip = File(outputDir, "${safeName.replace(Regex("[^A-Za-z0-9]+"), "_")}.zip")
            zipDir(work, zip)
            work.deleteRecursively()
            Assembled(zip, names)
        }
    }

    /**
     * Deterministic fixes for the Godot-4 mistakes these models repeat, verified
     * against the real Godot 4.3 parser. Each is a compile error, not a style
     * choice, so the repair is safe to apply unconditionally.
     */
    internal fun repair(code: String): String {
        var c = code
        // get_window_size() / OS.get_window_size() do not exist in Godot 4.
        c = c.replace(Regex("""(?:OS\.)?get_window_size\(\)"""), "get_viewport().get_visible_rect().size")
        // Demote `var x :=` to `var x =` ONLY where Godot genuinely cannot infer
        // the type: a null, an empty array or dictionary, a node lookup, or an
        // `as` cast. Those are hard "Cannot infer the type" parse errors —
        // verified live in Godot 4.3 — and `=` behaves identically at runtime.
        //
        // Demoting every `:=` was wrong: `:=` is ordinary, valid GDScript, and
        // the blanket rewrite mangled correct code — including this file's own
        // fallback game, which is built almost entirely from `var x := X.new()`.
        // A member read off a lowercase name is the common one: `event.position`
        // where `event` is an untyped parameter is a Variant, so nothing can be
        // inferred. A capitalised base is a class — `Vector3.ZERO`,
        // `WorldEnvironment.new()` — and infers fine, so it keeps its `:=`.
        c = c.replace(
            Regex(
                """(\bvar\s+\w+)\s*:=""" +
                    """(\s*(?:null\b|\[\s*\]|\{\s*\}|\$|get_node\b|[a-z_]\w*\s*\.|[^\n]*\bas\b))"""
            ),
            "$1 =$2",
        )
        // ProceduralSkyMaterial has no sun_latitude / sun_longitude in Godot 4.3
        // — the sun direction comes from the DirectionalLight3D. Drop those lines.
        c = c.replace(Regex("""(?m)^[ \t]*\w+\.sun_(?:latitude|longitude)\s*=.*\R?"""), "")
        // Environment's mode property is `background_mode`, not `background`; the
        // models often write `env.background = Environment.BG_SKY`, which Godot
        // 4.3 rejects at runtime ("Invalid assignment of property 'background'").
        c = c.replace(Regex("""\.background\s*=\s*(Environment\.BG_)"""), ".background_mode = $1")
        return c
    }

    private fun stripFences(code: String): String {
        var c = code.trim()
        if (c.startsWith("```")) {
            c = c.substringAfter('\n', c)          // drop the ``` or ```gdscript line
            c = c.substringBeforeLast("```").trimEnd()
        }
        return c
    }

    private fun projectGodot(name: String) = """
        ; Engine configuration file. Generated by VOID.
        config_version=5

        [application]
        config/name="$name"
        run/main_scene="res://main.tscn"
        config/features=PackedStringArray("4.3", "Mobile")
        config/icon="res://icon.svg"

        [rendering]
        renderer/rendering_method="mobile"
        renderer/rendering_method.mobile="mobile"
    """.trimIndent() + "\n"

    // A root Node3D named "Main" with the AI's script attached. Nothing else is
    // in the scene — the script builds the whole game in _ready().
    private val MAIN_TSCN = """
        [gd_scene load_steps=2 format=3 uid="uid://voidgamemainscn"]

        [ext_resource type="Script" path="res://main.gd" id="1_main"]

        [node name="Main" type="Node3D"]
        script = ExtResource("1_main")
    """.trimIndent() + "\n"

    private val ICON_SVG = """
        <svg width="128" height="128" xmlns="http://www.w3.org/2000/svg">
        <rect width="128" height="128" rx="24" fill="#7C3AED"/>
        <text x="64" y="82" font-size="56" text-anchor="middle" fill="white" font-family="sans-serif">V</text>
        </svg>
    """.trimIndent() + "\n"

    /**
     * A guaranteed-playable game, used when the AI's code can't be produced (the
     * model is down, the network drops, the answer is unusable). It auto-discovers
     * whatever .glb models shipped in res://models/ — using the first as the player
     * and the rest as collectibles — and falls back to primitives if there are
     * none, so it runs no matter what got generated. Hand-written and verified in
     * real Godot 4.3 (loads and runs clean, with and without models). This is what
     * makes "you always get a real, runnable zip" true.
     */
    val FALLBACK_GAME: String = """
        extends Node3D

        var player: CharacterBody3D
        var camera: Camera3D
        var score := 0
        var score_label: Label
        var speed := 8.0
        var gravity := 20.0
        var joystick_vector := Vector2.ZERO
        var touch_active := false
        var touch_start := Vector2.ZERO
        var models: Array = []

        func _ready() -> void:
        	_build_environment()
        	_build_ground()
        	models = _find_models()
        	_build_player()
        	_build_camera()
        	_spawn_collectibles()
        	_build_ui()

        func _find_models() -> Array:
        	var out: Array = []
        	var dir = DirAccess.open("res://models")
        	if dir:
        		dir.list_dir_begin()
        		var f = dir.get_next()
        		while f != "":
        			if f.ends_with(".glb") or f.ends_with(".gltf"):
        				var path = "res://models/" + f
        				if ResourceLoader.exists(path):
        					out.append(path)
        			f = dir.get_next()
        	return out

        func _instance_model(index: int) -> Node3D:
        	if models.size() > 0:
        		var path = models[index % models.size()]
        		var scene = load(path)
        		if scene:
        			var n = scene.instantiate()
        			if n is Node3D:
        				return n
        	return null

        func _build_environment() -> void:
        	var we := WorldEnvironment.new()
        	var env := Environment.new()
        	env.background_mode = Environment.BG_SKY
        	var sky := Sky.new()
        	var sky_mat := ProceduralSkyMaterial.new()
        	sky_mat.sky_top_color = Color(0.35, 0.55, 0.9)
        	sky_mat.sky_horizon_color = Color(0.8, 0.85, 0.95)
        	sky_mat.ground_bottom_color = Color(0.3, 0.35, 0.3)
        	sky.sky_material = sky_mat
        	env.sky = sky
        	env.ambient_light_source = Environment.AMBIENT_SOURCE_SKY
        	env.ambient_light_energy = 1.0
        	we.environment = env
        	add_child(we)
        	var light := DirectionalLight3D.new()
        	light.rotation_degrees = Vector3(-50, -30, 0)
        	light.shadow_enabled = true
        	add_child(light)

        func _build_ground() -> void:
        	var ground := StaticBody3D.new()
        	var col := CollisionShape3D.new()
        	var box := BoxShape3D.new()
        	box.size = Vector3(60, 1, 60)
        	col.shape = box
        	col.position = Vector3(0, -0.5, 0)
        	ground.add_child(col)
        	var mesh := MeshInstance3D.new()
        	var plane := PlaneMesh.new()
        	plane.size = Vector2(60, 60)
        	var mat := StandardMaterial3D.new()
        	mat.albedo_color = Color(0.25, 0.5, 0.3)
        	plane.material = mat
        	mesh.mesh = plane
        	ground.add_child(mesh)
        	add_child(ground)

        func _build_player() -> void:
        	player = CharacterBody3D.new()
        	var col := CollisionShape3D.new()
        	var cap := CapsuleShape3D.new()
        	cap.radius = 0.5
        	cap.height = 1.6
        	col.shape = cap
        	col.position = Vector3(0, 0.8, 0)
        	player.add_child(col)
        	var visual = _instance_model(0)
        	if visual:
        		visual.position = Vector3(0, 0, 0)
        		player.add_child(visual)
        	else:
        		var m := MeshInstance3D.new()
        		var cm := CapsuleMesh.new()
        		cm.radius = 0.5
        		cm.height = 1.6
        		var pmat := StandardMaterial3D.new()
        		pmat.albedo_color = Color(0.9, 0.3, 0.3)
        		cm.material = pmat
        		m.mesh = cm
        		m.position = Vector3(0, 0.8, 0)
        		player.add_child(m)
        	player.position = Vector3(0, 1, 0)
        	add_child(player)

        func _build_camera() -> void:
        	camera = Camera3D.new()
        	camera.position = Vector3(0, 8, 12)
        	camera.rotation_degrees = Vector3(-30, 0, 0)
        	add_child(camera)

        func _spawn_collectibles() -> void:
        	for i in range(8):
        		var area := Area3D.new()
        		var col := CollisionShape3D.new()
        		var sph := SphereShape3D.new()
        		sph.radius = 0.8
        		col.shape = sph
        		area.add_child(col)
        		var visual = null
        		if models.size() > 1:
        			visual = _instance_model(i + 1)
        		if visual:
        			area.add_child(visual)
        		else:
        			var m := MeshInstance3D.new()
        			var sm := SphereMesh.new()
        			sm.radius = 0.5
        			sm.height = 1.0
        			var gmat := StandardMaterial3D.new()
        			gmat.albedo_color = Color(1.0, 0.85, 0.1)
        			gmat.emission_enabled = true
        			gmat.emission = Color(1.0, 0.7, 0.0)
        			sm.material = gmat
        			m.mesh = sm
        			area.add_child(m)
        		var ang = randf() * TAU
        		var rad = 6.0 + randf() * 18.0
        		area.position = Vector3(cos(ang) * rad, 1.0, sin(ang) * rad)
        		area.body_entered.connect(_on_pickup.bind(area))
        		add_child(area)

        func _on_pickup(body: Node, area: Area3D) -> void:
        	if body == player:
        		score += 10
        		if score_label:
        			score_label.text = "Score: %d" % score
        		area.queue_free()

        func _build_ui() -> void:
        	var layer := CanvasLayer.new()
        	add_child(layer)
        	score_label = Label.new()
        	score_label.text = "Score: 0"
        	score_label.position = Vector2(20, 20)
        	score_label.add_theme_font_size_override("font_size", 28)
        	layer.add_child(score_label)

        func _unhandled_input(event: InputEvent) -> void:
        	if event is InputEventScreenTouch:
        		var t = event as InputEventScreenTouch
        		if t.pressed:
        			touch_active = true
        			touch_start = t.position
        		else:
        			touch_active = false
        			joystick_vector = Vector2.ZERO
        	elif event is InputEventScreenDrag:
        		var d = event as InputEventScreenDrag
        		if touch_active:
        			var diff = d.position - touch_start
        			joystick_vector = diff.limit_length(100.0) / 100.0

        func _physics_process(delta: float) -> void:
        	if not player:
        		return
        	var input := Input.get_vector("ui_left", "ui_right", "ui_up", "ui_down")
        	var move := input
        	if joystick_vector.length() > 0.1:
        		move = joystick_vector
        	var dir := Vector3(move.x, 0, move.y)
        	player.velocity.x = dir.x * speed
        	player.velocity.z = dir.z * speed
        	if not player.is_on_floor():
        		player.velocity.y -= gravity * delta
        	else:
        		player.velocity.y = 0.0
        	player.move_and_slide()
        	if camera:
        		camera.position = player.position + Vector3(0, 8, 12)
    """.trimIndent() + "\n"

    private fun readme(name: String, models: List<String>) = """
        $name — built with VOID

        How to run:
        1. Copy this folder to your phone (or unzip it somewhere Godot can reach).
        2. Open Godot 4.3+, tap Import, and pick the project.godot in this folder.
        3. Let Godot import the models (first open takes a moment), then press Play.

        The whole game is in main.gd. Models are in models/:
        ${models.joinToString("\n        ") { "- $it.glb" }}

        Edit main.gd to change the game — it is plain GDScript.
    """.trimIndent() + "\n"

    private fun zipDir(source: File, zip: File) {
        ZipOutputStream(zip.outputStream().buffered()).use { zos ->
            source.walkTopDown().filter { it.isFile }.forEach { file ->
                val entry = source.toURI().relativize(file.toURI()).path
                zos.putNextEntry(ZipEntry(entry))
                file.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
    }
}
