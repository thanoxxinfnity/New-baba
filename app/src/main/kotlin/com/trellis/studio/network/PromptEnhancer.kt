package com.trellis.studio.network

import com.trellis.studio.data.model.ChatTurn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Turns a short idea into a rich, vivid generation prompt — the "✨ Magic Prompt"
 * button. A quick LLM pass adds subject, style, lighting, lens and mood so the
 * image or 3D model comes out far better than a bare few words.
 */
class PromptEnhancer(private val nim: NimClient = NimClient()) {

    suspend fun enhance(
        apiKey: String,
        model: String,
        idea: String,
        forImage: Boolean = true,
    ): Result<String> = withContext(Dispatchers.IO) {
        if (idea.isBlank()) return@withContext Result.failure(Exception("Type an idea first."))
        val sys = if (forImage) IMAGE_SYS else MODEL_SYS
        nim.chat(
            apiKey = apiKey,
            model = model,
            turns = listOf(ChatTurn("system", sys), ChatTurn("user", idea.trim())),
            maxTokens = 220,
            temperature = 0.9,
        ).mapCatching { r ->
            r.content.ifBlank { r.reasoning.orEmpty() }
                .trim().trim('"').replace("\n", " ")
                .ifBlank { throw Exception("Couldn't enhance that — try again.") }
        }
    }

    private companion object {
        const val IMAGE_SYS =
            "You expand a short idea into ONE vivid image-generation prompt. Add concrete " +
                "subject detail, art style, lighting, colour mood, camera/lens and quality words " +
                "(e.g. 'ultra detailed, 8k, cinematic'). Keep it one line, under 60 words. " +
                "Output ONLY the prompt text — no quotes, no preamble."
        const val MODEL_SYS =
            "You expand a short idea into ONE prompt for an image-to-3D model. Describe a SINGLE " +
                "complete object, its material and colour, on a plain background, fully textured. " +
                "One line, under 40 words. Output ONLY the prompt text."
    }
}
