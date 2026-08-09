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
        // Demote every inferred `var x :=` to a plain `var x =`. The models keep
        // using `:=` on right-hand sides whose type Godot can't infer (an untyped
        // `event as …` result, a subtraction of Variant properties, …), which is a
        // hard "Cannot infer the type" parse error — verified live in Godot 4.3.
        // `=` is byte-for-byte identical at runtime (it just makes the variable
        // dynamically typed), so this removes a whole class of parse errors with
        // no behaviour change. Only `var` declarations are touched — `const`,
        // comparisons (`==`, `<=`) and typed `var x: T =` are left alone.
        c = c.replace(Regex("""(\bvar\s+\w+)\s*:="""), "$1 =")
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
