package com.trellis.studio.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * A disposable "temp inbox" on the free mail.tm service: make throwaway email
 * addresses and read what arrives (verification codes, newsletters) without
 * exposing your real inbox. A privacy tool — like Apple's Hide My Email.
 *
 * Only the address + password are stored on device; the login token is fetched
 * again on demand, so an inbox is never lost and can be re-opened any time.
 */
class TempMailClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val jsonMedia = "application/json".toMediaType()
    private val base = "https://api.mail.tm"

    data class Account(val address: String, val password: String)
    data class Message(
        val id: String,
        val from: String,
        val subject: String,
        val intro: String,
        val date: String,
        val seen: Boolean,
    )

    /** Creates a fresh random disposable address and returns its credentials. */
    suspend fun create(): Result<Account> = withContext(Dispatchers.IO) {
        runCatching {
            val domain = firstDomain()
            val local = "void" + (100000..999999).random() + System.currentTimeMillis().toString().takeLast(4)
            val address = "$local@$domain"
            val password = "Void!" + (100000..999999).random()
            val body = buildJsonObject { put("address", address); put("password", password) }
                .toString().toRequestBody(jsonMedia)
            val req = Request.Builder().url("$base/accounts").post(body).build()
            client.newCall(req).execute().use { resp ->
                if (resp.code == 422) throw Exception("That address is taken — try again.")
                if (!resp.isSuccessful) throw Exception("Couldn't create an inbox (${resp.code}). Try again.")
            }
            Account(address, password)
        }
    }

    private fun firstDomain(): String {
        val req = Request.Builder().url("$base/domains").header("Accept", "application/json").get().build()
        client.newCall(req).execute().use { resp ->
            val txt = resp.body?.string().orEmpty()
            val el = json.parseToJsonElement(txt)
            val list = runCatching { el.jsonObject["hydra:member"]!!.jsonArray }.getOrElse { el.jsonArray }
            return list.firstOrNull()?.jsonObject?.get("domain")?.jsonPrimitive?.content
                ?: throw Exception("No mail domains available right now.")
        }
    }

    private fun token(acc: Account): String {
        val body = buildJsonObject { put("address", acc.address); put("password", acc.password) }
            .toString().toRequestBody(jsonMedia)
        val req = Request.Builder().url("$base/token").post(body).build()
        client.newCall(req).execute().use { resp ->
            val txt = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw Exception("Couldn't open that inbox (${resp.code}).")
            return json.parseToJsonElement(txt).jsonObject["token"]?.jsonPrimitive?.content
                ?: throw Exception("No login token returned.")
        }
    }

    /** Lists messages in [acc]'s inbox, newest first. */
    suspend fun inbox(acc: Account): Result<List<Message>> = withContext(Dispatchers.IO) {
        runCatching {
            val tok = token(acc)
            val req = Request.Builder().url("$base/messages")
                .header("Authorization", "Bearer $tok").get().build()
            client.newCall(req).execute().use { resp ->
                val txt = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw Exception("Couldn't load the inbox (${resp.code}).")
                val members = json.parseToJsonElement(txt).jsonObject["hydra:member"]?.jsonArray ?: return@use emptyList()
                members.map { m ->
                    val o = m.jsonObject
                    Message(
                        id = o["id"]?.jsonPrimitive?.content.orEmpty(),
                        from = o["from"]?.jsonObject?.get("address")?.jsonPrimitive?.content.orEmpty(),
                        subject = o["subject"]?.jsonPrimitive?.content.orEmpty().ifBlank { "(no subject)" },
                        intro = o["intro"]?.jsonPrimitive?.content.orEmpty(),
                        date = o["createdAt"]?.jsonPrimitive?.content.orEmpty().take(16).replace('T', ' '),
                        seen = o["seen"]?.jsonPrimitive?.content?.toBoolean() ?: false,
                    )
                }
            }
        }
    }

    /** Fetches the full text of one message. */
    suspend fun read(acc: Account, id: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val tok = token(acc)
            val req = Request.Builder().url("$base/messages/$id")
                .header("Authorization", "Bearer $tok").get().build()
            client.newCall(req).execute().use { resp ->
                val txt = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw Exception("Couldn't open the message (${resp.code}).")
                val o = json.parseToJsonElement(txt).jsonObject
                o["text"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                    ?: o["html"]?.jsonArray?.joinToString("\n") { it.jsonPrimitive.content }
                        ?.replace(Regex("<[^>]+>"), " ")?.replace(Regex("\\s+"), " ")?.trim()
                    ?: o["intro"]?.jsonPrimitive?.content
                    ?: "(empty message)"
            }
        }
    }
}
