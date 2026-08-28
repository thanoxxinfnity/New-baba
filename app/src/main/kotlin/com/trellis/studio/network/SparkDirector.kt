package com.trellis.studio.network

import com.trellis.studio.data.model.ChatTurn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Spark — describe an app, get a real one. Turns a plain-language idea into ONE
 * self-contained HTML document (inline CSS + JS) that runs immediately in a
 * WebView, the way Gemini's Spark/Canvas builds a live mini-app.
 *
 * The whole app is one file with nothing external, so it works offline and can
 * be shared as a single .html. Verified live: the model returns a complete,
 * self-contained document with no external URLs.
 */
class SparkDirector(private val nim: NimClient = NimClient()) {

    suspend fun build(
        apiKey: String,
        model: String,
        idea: String,
    ): Result<String> = withContext(Dispatchers.IO) {
        if (idea.isBlank()) return@withContext Result.failure(Exception("Describe the app you want."))
        nim.chat(
            apiKey = apiKey,
            model = model,
            turns = listOf(ChatTurn("system", SYSTEM), ChatTurn("user", "Build this app:\n$idea")),
            maxTokens = 8000,
            temperature = 0.5,
            readTimeoutSeconds = 300,   // a whole app takes a couple of minutes
        ).mapCatching { r ->
            val html = clean(r.content.ifBlank { r.reasoning.orEmpty() })
            require(html.contains("<") && html.contains("</")) {
                "The model didn't return a usable app. Try again or rephrase."
            }
            html
        }
    }

    /** Strips code fences and anything before <!DOCTYPE/<html so it's pure HTML. */
    private fun clean(raw: String): String {
        var c = raw.trim()
        if (c.startsWith("```")) {
            c = c.substringAfter('\n', c).substringBeforeLast("```").trim()
        }
        val lower = c.lowercase()
        val start = lower.indexOf("<!doctype").let { if (it >= 0) it else lower.indexOf("<html") }
        if (start > 0) c = c.substring(start)
        return c
    }

    private companion object {
        val SYSTEM = """
            You are Spark, an expert app builder. You output ONE complete,
            self-contained HTML document that runs a small, polished interactive
            app inside a mobile WebView.

            HARD RULES:
            - Output ONLY the HTML, starting with <!DOCTYPE html>. No markdown
              fences, no commentary before or after.
            - EVERYTHING inline: put all CSS in a <style> and all JS in a <script>
              in the same file. NEVER reference any external URL, CDN, font,
              image or library. No <img src="http...">, no fetch(). It must work
              fully offline.
            - Mobile-first: big touch targets, responsive layout, a modern dark
              theme with tasteful gradients, rounded corners and smooth CSS
              transitions/animations. Make it feel premium.
            - The app must actually WORK and be genuinely useful or fun. Wire up
              all buttons and state with vanilla JavaScript.
            - Keep it to one screen where possible; use clear headings and helpful
              empty states.

            Build exactly what the user asked for, complete and runnable.
        """.trimIndent()
    }
}
