package com.rokid.inbox.phone.channels

import android.util.Base64
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.rokid.inbox.contracts.Chat
import com.rokid.inbox.contracts.ChannelKind
import com.rokid.inbox.contracts.ChatType
import com.rokid.inbox.contracts.Message
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Telegram channel backed by the self-hosted GramJS bridge (see /telegram-bridge).
 * The phone only speaks HTTP; MTProto + the account session live on the server,
 * exactly like WhatsApp goes through the user's Evolution API server.
 */
class TelegramService(
    override val boxId: String,
    serverUrl: String,
    private val apiKey: String,
) : ChannelService {
    override val kind = ChannelKind.TELEGRAM
    override val canSend = true
    override val canReact = true

    private val serverUrl = serverUrl.trimEnd('/')
    private val jsonType = "application/json".toMediaType()

    private fun post(path: String, body: JsonObject): String {
        val request = Request.Builder()
            .url("$serverUrl$path")
            .addHeader("Content-Type", "application/json")
            .addHeader("apikey", apiKey)
            .post(body.toString().toRequestBody(jsonType))
            .build()
        Http.client.newCall(request).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) {
                throw RuntimeException("Telegram bridge ${res.code}: ${text.take(300).ifBlank { res.message }}")
            }
            return text
        }
    }

    override suspend fun ping() {
        val request = Request.Builder()
            .url("$serverUrl/health")
            .addHeader("apikey", apiKey)
            .get()
            .build()
        Http.client.newCall(request).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) throw RuntimeException("Telegram bridge ${res.code}: ${text.take(200).ifBlank { res.message }}")
            val obj = Http.parse(text).obj()
            if (obj?.get("authorized")?.asBooleanOrFalse() != true) {
                throw RuntimeException("Bridge reachable, but the account is not logged in. Run 'npm run login' on the server.")
            }
        }
    }

    override suspend fun listChats(limit: Int): List<Chat> {
        val raw = Http.parse(post("/chats", JsonObject().apply { addProperty("limit", limit) }))
        return (raw.arr() ?: JsonArray()).mapNotNull { it as? JsonObject }.map { c ->
            Chat(
                channel = ChannelKind.TELEGRAM,
                boxId = boxId,
                id = c.str("id"),
                name = c.str("name").ifBlank { "Telegram" },
                type = parseType(c.str("type")),
                unreadCount = c.intOrNull("unreadCount") ?: 0,
                lastMessageDate = c.str("lastMessageDate").ifBlank { null },
                lastMessagePreview = c.str("lastMessagePreview"),
            )
        }
    }

    override suspend fun listMessages(chatId: String, limit: Int): List<Message> {
        val body = JsonObject().apply {
            addProperty("chatId", chatId)
            addProperty("limit", limit)
        }
        val raw = Http.parse(post("/messages", body))
        val msgs = (raw.arr() ?: JsonArray()).mapNotNull { it as? JsonObject }.map { m ->
            Message(
                id = m.str("id"),
                text = m.str("text"),
                media = m.str("media").ifBlank { null },
                date = m.str("date").ifBlank { null },
                isOutgoing = m.get("isOutgoing")?.asBooleanOrFalse() ?: false,
                senderName = m.str("senderName"),
                durationSec = m.intOrNull("durationSec") ?: 0,
                fileName = m.str("fileName"),
            )
        }
        return msgs.reversed() // bridge returns newest-first; show oldest-first
    }

    override suspend fun sendText(chatId: String, text: String, replyToId: String, replyFromMe: Boolean) {
        post("/sendText", JsonObject().apply {
            addProperty("chatId", chatId)
            addProperty("text", text)
            if (replyToId.isNotBlank()) addProperty("replyToId", replyToId)
        })
    }

    override suspend fun sendVoice(chatId: String, wav: ByteArray, durationSec: Int, replyToId: String, replyFromMe: Boolean) {
        post("/sendVoice", JsonObject().apply {
            addProperty("chatId", chatId)
            addProperty("audioBase64", Base64.encodeToString(wav, Base64.NO_WRAP))
            addProperty("durationSec", durationSec)
            if (replyToId.isNotBlank()) addProperty("replyToId", replyToId)
        })
    }

    override suspend fun sendReaction(chatId: String, messageId: String, emoji: String, fromMe: Boolean) {
        post("/sendReaction", JsonObject().apply {
            addProperty("chatId", chatId)
            addProperty("messageId", messageId)
            addProperty("emoji", emoji)
        })
    }

    override suspend fun markAsRead(chatId: String, messages: List<Message>) {
        runCatching { post("/markRead", JsonObject().apply { addProperty("chatId", chatId) }) }
    }

    override suspend fun fetchMedia(chatId: String, message: Message): ByteArray? {
        val res = Http.parse(
            post("/media", JsonObject().apply {
                addProperty("chatId", chatId)
                addProperty("messageId", message.id)
            }),
        ).obj()
        val b64 = res.str("base64")
        return if (b64.isNotBlank()) Base64.decode(b64, Base64.NO_WRAP) else null
    }

    private companion object {
        fun parseType(t: String): ChatType = when (t) {
            "group" -> ChatType.GROUP
            "channel" -> ChatType.CHANNEL
            else -> ChatType.USER
        }

        fun com.google.gson.JsonElement.asBooleanOrFalse(): Boolean =
            runCatching { if (isJsonPrimitive) asBoolean else false }.getOrDefault(false)
    }
}
