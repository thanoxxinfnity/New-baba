package com.trellis.studio.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Reads YouTube via the Data API v3 — search only. It is a reference source for
 * the video director (what popular videos in a space are called, for stylistic
 * cues); it does not, and cannot, generate video. Optional: without a key the
 * pipeline just skips the reference step.
 */
class YouTubeClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Titles of popular videos matching [query], most-viewed first. */
    suspend fun referenceTitles(apiKey: String, query: String, max: Int = 5): List<String> =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) return@withContext emptyList()
            val url = "https://www.googleapis.com/youtube/v3/search?part=snippet&type=video" +
                "&order=viewCount&maxResults=$max&q=${URLEncoder.encode(query, "UTF-8")}&key=$apiKey"
            runCatching {
                client.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
                    if (!resp.isSuccessful) return@use emptyList()
                    val body = resp.body?.string().orEmpty()
                    json.parseToJsonElement(body).jsonObject["items"]?.jsonArray
                        ?.mapNotNull {
                            it.jsonObject["snippet"]?.jsonObject
                                ?.get("title")?.jsonPrimitive?.content
                        }.orEmpty()
                }
            }.getOrDefault(emptyList())
        }
}
