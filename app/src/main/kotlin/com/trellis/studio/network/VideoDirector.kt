package com.trellis.studio.network

import com.trellis.studio.data.model.ChatTurn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Plans a video as a storyboard: a title, a visual style, and an ordered list of
 * scenes. Each scene carries a detailed image prompt, a one-line caption, and a
 * duration. This is the "director" half of the free video pipeline — the images
 * are generated per scene and then assembled into an MP4.
 *
 * The prompts are written to flow, each continuing from the last, so the finished
 * slideshow reads as one story rather than disconnected pictures.
 */
class VideoDirector(private val nim: NimClient = NimClient()) {

    data class Scene(val prompt: String, val caption: String, val seconds: Float)
    data class Storyboard(val title: String, val style: String, val scenes: List<Scene>)

    /**
     * @param reference optional YouTube titles for the model to take stylistic
     *   cues from — never required.
     */
    suspend fun plan(
        apiKey: String,
        model: String,
        idea: String,
        style: String,
        targetSeconds: Int,
        reference: List<String> = emptyList(),
    ): Result<Storyboard> = withContext(Dispatchers.IO) {
        // Aim for ~3.5s a scene; more scenes for a longer target.
        val sceneCount = (targetSeconds / 3.5f).toInt().coerceIn(3, 60)
        val refLine = if (reference.isEmpty()) ""
        else "\nFor style reference, popular videos in this space are titled: " +
            reference.take(5).joinToString("; ") + ". Take cues, don't copy."

        val user = buildString {
            appendLine("IDEA: ${idea.trim()}")
            appendLine("STYLE: ${style.ifBlank { "cinematic" }}")
            appendLine("Make exactly $sceneCount scenes, ~${targetSeconds}s total.$refLine")
            append("Output the storyboard JSON now.")
        }

        nim.chat(
            apiKey = apiKey,
            model = model,
            turns = listOf(ChatTurn("system", systemPrompt(style)), ChatTurn("user", user)),
            maxTokens = 4000,
            temperature = 0.6,
        ).mapCatching { reply ->
            parse(reply.content.ifBlank { reply.reasoning.orEmpty() })
                ?: error("The model didn't return a usable storyboard. Try again.")
        }
    }

    private fun systemPrompt(style: String) = """
        You are a video director. Turn an idea into a JSON storyboard. Output ONLY
        JSON, no prose, no code fences:
        {"title":"...","style":"...","scenes":[
          {"prompt":"detailed image prompt","caption":"one short on-screen line","seconds":3},
          ...
        ]}
        Rules:
        - Every scene's "prompt" is a rich, standalone image description in the
          "$style" style, and visually CONTINUES from the previous scene so the
          sequence tells one smooth story (same characters, setting, lighting).
        - "caption" is a short line (or "") shown over that scene.
        - "seconds" is 2 to 5.
        - Keep the exact number of scenes requested.
    """.trimIndent()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Brace-matched extraction + a tolerant read of the scenes array. */
    private fun parse(raw: String): Storyboard? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val obj = runCatching {
            json.parseToJsonElement(raw.substring(start, end + 1)) as? JsonObject
        }.getOrNull() ?: return null

        fun JsonObject.str(key: String): String? =
            runCatching { this[key]?.jsonPrimitive?.contentOrNull }.getOrNull()

        val title = obj.str("title") ?: "VOID Video"
        val style = obj.str("style").orEmpty()
        val scenes = runCatching { obj["scenes"]?.jsonArray }.getOrNull()
            ?.mapNotNull { el ->
                val s = runCatching { el.jsonObject }.getOrNull() ?: return@mapNotNull null
                val p = s.str("prompt")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val cap = s.str("caption").orEmpty()
                val sec = s.str("seconds")?.toFloatOrNull() ?: 3f
                Scene(p, cap, sec.coerceIn(1.5f, 6f))
            }.orEmpty()

        return if (scenes.isEmpty()) null else Storyboard(title, style, scenes)
    }
}
