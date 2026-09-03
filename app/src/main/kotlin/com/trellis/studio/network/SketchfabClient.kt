package com.trellis.studio.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Sketchfab's public Data API: search by name, resolve a shared link, and
 * download the models whose authors made them downloadable.
 *
 * Search and model details are open. Downloading needs a free Sketchfab API
 * token (Settings → Password & API on sketchfab.com), which the download
 * endpoint checks — it hands back short-lived signed URLs rather than the file.
 *
 * Only models flagged downloadable can be fetched. That flag is the author's
 * decision, and this client does not try to work around it: a store model or
 * one the author kept view-only is reported as such.
 */
class SketchfabClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    data class Model(
        val uid: String,
        val name: String,
        val author: String,
        val license: String,
        val downloadable: Boolean,
        val thumbnail: String?,
        val faceCount: Long,
        val viewerUrl: String,
    )

    /** Searches by name. Downloadable-only by default, which is what's usable. */
    suspend fun search(
        query: String,
        downloadableOnly: Boolean = true,
        count: Int = 24,
    ): Result<List<Model>> = withContext(Dispatchers.IO) {
        runCatching {
            require(query.isNotBlank()) { "Type something to search for." }
            val url = "https://api.sketchfab.com/v3/search?type=models" +
                "&q=${java.net.URLEncoder.encode(query.trim(), "UTF-8")}" +
                (if (downloadableOnly) "&downloadable=true" else "") +
                "&count=$count"
            val body = get(url, token = null)
            json.parseToJsonElement(body).jsonObject["results"]?.jsonArray
                ?.mapNotNull { runCatching { parseModel(it.jsonObject) }.getOrNull() }
                ?: emptyList()
        }
    }

    /** Details for one model, by uid or by any Sketchfab URL. */
    suspend fun detail(uidOrUrl: String): Result<Model> = withContext(Dispatchers.IO) {
        runCatching {
            val uid = extractUid(uidOrUrl)
                ?: throw IllegalArgumentException(
                    "That doesn't look like a Sketchfab model link. Copy the URL from the " +
                        "model's page — it ends in a long id."
                )
            val body = get("https://api.sketchfab.com/v3/models/$uid", token = null)
            parseModel(json.parseToJsonElement(body).jsonObject)
        }
    }

    /**
     * Asks for the glTF archive URL. Sketchfab returns a signed link that
     * expires in about an hour, not the bytes themselves.
     */
    suspend fun downloadUrl(uid: String, apiToken: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(apiToken.isNotBlank()) {
                    "Add your Sketchfab API token in Settings to download. It's free — " +
                        "sketchfab.com → Settings → Password & API."
                }
                val body = get("https://api.sketchfab.com/v3/models/$uid/download", apiToken)
                val root = json.parseToJsonElement(body).jsonObject
                // gltf is the portable one; source is the author's original format
                // and is often a .blend or .max this app cannot open.
                val gltf = root["gltf"]?.jsonObject ?: root["glb"]?.jsonObject
                gltf?.get("url")?.jsonPrimitive?.content
                    ?: throw IllegalStateException(
                        "Sketchfab returned no glTF download for this model."
                    )
            }
        }

    /** Fetches the archive bytes from the signed URL. */
    suspend fun fetch(url: String): Result<ByteArray> = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(Request.Builder().url(url).get().build()).execute().use { r ->
                if (!r.isSuccessful) throw Exception("The download link failed (${r.code}).")
                r.body?.bytes() ?: throw Exception("The download was empty.")
            }
        }
    }

    private fun get(url: String, token: String?): String {
        val req = Request.Builder().url(url).get()
            .apply { if (!token.isNullOrBlank()) header("Authorization", "Token $token") }
            .build()
        http.newCall(req).execute().use { r ->
            val body = r.body?.string().orEmpty()
            when {
                r.isSuccessful -> return body
                r.code == 401 -> throw Exception(
                    "Sketchfab rejected the API token. Check it in Settings."
                )
                r.code == 403 -> throw Exception(
                    "This model isn't available to download — the author kept it view-only " +
                        "or it's a paid store model."
                )
                r.code == 404 -> throw Exception("No Sketchfab model with that id.")
                r.code == 429 -> throw Exception("Sketchfab is rate-limiting. Wait a moment.")
                else -> throw Exception("Sketchfab error (${r.code}).")
            }
        }
    }

    private fun parseModel(o: kotlinx.serialization.json.JsonObject): Model {
        val uid = o["uid"]!!.jsonPrimitive.content
        return Model(
            uid = uid,
            name = o["name"]?.jsonPrimitive?.content.orEmpty().ifBlank { "Untitled" },
            author = o["user"]?.jsonObject?.get("displayName")?.jsonPrimitive?.content.orEmpty(),
            license = o["license"]?.jsonObject?.get("label")?.jsonPrimitive?.content
                ?: "License not stated",
            downloadable = o["isDownloadable"]?.jsonPrimitive?.content == "true",
            thumbnail = o["thumbnails"]?.jsonObject?.get("images")?.jsonArray
                ?.maxByOrNull { it.jsonObject["width"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0 }
                ?.jsonObject?.get("url")?.jsonPrimitive?.content,
            faceCount = o["faceCount"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
            viewerUrl = o["viewerUrl"]?.jsonPrimitive?.content
                ?: "https://sketchfab.com/models/$uid",
        )
    }

    companion object {
        /**
         * Pulls the model id out of whatever the user pasted. Sketchfab URLs end
         * in a 32-character hex id, sometimes with a slug in front of it.
         */
        fun extractUid(input: String): String? {
            val s = input.trim()
            if (s.matches(Regex("[0-9a-fA-F]{32}"))) return s
            return Regex("([0-9a-fA-F]{32})").find(s)?.groupValues?.get(1)
        }
    }
}
