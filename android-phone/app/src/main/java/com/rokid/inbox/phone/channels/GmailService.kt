package com.rokid.inbox.phone.channels

import com.google.gson.JsonObject
import com.rokid.inbox.contracts.Chat
import com.rokid.inbox.contracts.ChannelKind
import com.rokid.inbox.contracts.ChatType
import com.rokid.inbox.contracts.Message
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant

/**
 * Gmail as a read-only box: each thread is a chat, each email a message. Ported
 * from even-inbox `src/channels/gmail.ts`. The user supplies their own OAuth
 * Client ID + Secret + Refresh Token (scope gmail.modify); nothing hardcoded.
 */
class GmailService(
    override val boxId: String,
    private val clientId: String,
    private val clientSecret: String,
    private val refreshToken: String,
) : ChannelService {
    override val kind = ChannelKind.GMAIL
    override val canSend = false

    private var accessToken = ""
    private var tokenExpiry = 0L
    private var myEmail = ""

    private fun token(): String {
        if (accessToken.isNotBlank() && System.currentTimeMillis() < tokenExpiry - 30_000) return accessToken
        val form = FormBody.Builder()
            .add("client_id", clientId)
            .add("client_secret", clientSecret)
            .add("refresh_token", refreshToken)
            .add("grant_type", "refresh_token")
            .build()
        val request = Request.Builder().url(TOKEN_URL).post(form).build()
        Http.client.newCall(request).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) throw RuntimeException("Gmail OAuth ${res.code}: ${text.take(200)}")
            val obj = Http.parse(text).obj()
            accessToken = obj.str("access_token")
            val expiresIn = obj.longOrNull("expires_in") ?: 3600
            tokenExpiry = System.currentTimeMillis() + expiresIn * 1000
            return accessToken
        }
    }

    private fun get(path: String): String {
        val request = Request.Builder().url("$API$path").addHeader("Authorization", "Bearer ${token()}").get().build()
        Http.client.newCall(request).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) throw RuntimeException("Gmail API ${res.code}: ${text.take(200)}")
            return text
        }
    }

    override suspend fun ping() {
        myEmail = Http.parse(get("/profile")).obj().str("emailAddress")
    }

    private fun whoAmI(): String {
        if (myEmail.isNotBlank()) return myEmail
        myEmail = runCatching { Http.parse(get("/profile")).obj().str("emailAddress") }.getOrDefault("")
        return myEmail
    }

    override suspend fun listChats(limit: Int): List<Chat> {
        val list = Http.parse(get("/threads?labelIds=INBOX&maxResults=${minOf(limit, 20)}"))
        val threads = list.obj().optArr("threads") ?: return emptyList()
        return threads.mapNotNull { it as? JsonObject }.mapNotNull { t ->
            runCatching {
                val id = t.str("id")
                val full = Http.parse(get("/threads/$id?format=metadata&metadataHeaders=From&metadataHeaders=Subject")).obj()
                val msgs = full.optArr("messages")?.mapNotNull { m -> m as? JsonObject } ?: emptyList()
                val last = msgs.lastOrNull()
                val subject = header(last, "Subject").ifBlank { t.str("snippet") }.ifBlank { "(sem assunto)" }
                val unread = msgs.any { m -> (m.optArr("labelIds")?.any { it.asString == "UNREAD" }) == true }
                Chat(
                    channel = ChannelKind.GMAIL,
                    boxId = boxId,
                    id = id,
                    name = oneLine(subject),
                    type = ChatType.USER,
                    unreadCount = if (unread) 1 else 0,
                    lastMessageDate = last.longOrNull("internalDate")?.let { Instant.ofEpochMilli(it).toString() },
                    lastMessagePreview = oneLine(t.str("snippet")).take(120),
                )
            }.getOrNull()
        }
    }

    override suspend fun listMessages(chatId: String, limit: Int): List<Message> {
        val me = whoAmI()
        val full = Http.parse(get("/threads/$chatId?format=full")).obj()
        val msgs = (full.optArr("messages")?.mapNotNull { it as? JsonObject } ?: emptyList()).map { m ->
            val from = header(m, "From")
            Message(
                id = m.str("id"),
                text = extractText(m.optObj("payload")).ifBlank { m.str("snippet") },
                media = null,
                date = m.longOrNull("internalDate")?.let { Instant.ofEpochMilli(it).toString() },
                isOutgoing = me.isNotBlank() && from.contains(me),
                senderName = displayName(from),
            )
        }
        return msgs.sortedBy { epoch(it.date) }.takeLast(limit)
    }

    override suspend fun sendText(chatId: String, text: String, replyToId: String, replyFromMe: Boolean) =
        throw UnsupportedOperationException("Gmail é somente leitura")
    override suspend fun sendVoice(chatId: String, wav: ByteArray, durationSec: Int, replyToId: String, replyFromMe: Boolean) =
        throw UnsupportedOperationException("Gmail é somente leitura")

    override suspend fun markAsRead(chatId: String, messages: List<Message>) {
        val body = JsonObject().apply {
            add("removeLabelIds", com.google.gson.JsonArray().apply { add("UNREAD") })
        }
        val req = Request.Builder()
            .url("$API/threads/$chatId/modify")
            .addHeader("Authorization", "Bearer ${token()}")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        runCatching { Http.client.newCall(req).execute().use { } }
    }

    private companion object {
        const val TOKEN_URL = "https://oauth2.googleapis.com/token"
        const val API = "https://gmail.googleapis.com/gmail/v1/users/me"

        fun header(m: JsonObject?, name: String): String {
            val headers = m.optObj("payload").optArr("headers") ?: return ""
            return headers.mapNotNull { it as? JsonObject }
                .firstOrNull { it.str("name").equals(name, ignoreCase = true) }
                ?.str("value") ?: ""
        }

        fun displayName(from: String): String {
            val m = Regex("^\\s*\"?([^\"<]+?)\"?\\s*<[^>]+>").find(from)
            return (m?.groupValues?.get(1) ?: from).trim()
        }

        fun oneLine(s: String): String = s.replace(Regex("\\s+"), " ").trim()

        fun extractText(part: JsonObject?): String {
            part ?: return ""
            val mime = part.str("mimeType")
            val data = part.optObj("body").str("data")
            if (mime == "text/plain" && data.isNotBlank()) return decodeB64Url(data)
            part.optArr("parts")?.mapNotNull { it as? JsonObject }?.forEach { p ->
                val t = extractText(p)
                if (t.isNotBlank()) return t
            }
            if (mime == "text/html" && data.isNotBlank()) {
                return decodeB64Url(data).replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()
            }
            return ""
        }

        fun decodeB64Url(data: String): String = runCatching {
            String(android.util.Base64.decode(data, android.util.Base64.URL_SAFE), Charsets.UTF_8)
        }.getOrDefault("")

        fun epoch(iso: String?): Long {
            iso ?: return 0
            return runCatching { Instant.parse(iso).toEpochMilli() }.getOrDefault(0)
        }
    }
}
