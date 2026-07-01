package com.rokid.inbox.phone.channels

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.rokid.inbox.contracts.Chat
import com.rokid.inbox.contracts.ChannelKind
import com.rokid.inbox.contracts.ChatType
import com.rokid.inbox.contracts.Message
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant
import android.util.Base64

/**
 * WhatsApp channel backed by a user-provided Evolution API server. Ported from
 * even-inbox `src/channels/whatsapp.ts`. Nothing is hardcoded; the server URL,
 * instance and key come from the phone settings screen.
 */
class WhatsAppService(
    override val boxId: String,
    serverUrl: String,
    private val instance: String,
    private val apiKey: String,
) : ChannelService {
    override val kind = ChannelKind.WHATSAPP
    override val canSend = true
    override val canReact = true

    private val serverUrl = serverUrl.trimEnd('/')
    private val jsonType = "application/json".toMediaType()
    private var nameCache: Map<String, String>? = null

    // Path-segment encoding (space -> %20), matching JS encodeURIComponent.
    // java.net.URLEncoder must NOT be used here: it encodes space as "+", which
    // Evolution reads as a different instance name and returns 404.
    private val instancePath: String get() = android.net.Uri.encode(instance)

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
                throw RuntimeException("Evolution API ${res.code}: ${text.take(300).ifBlank { res.message }}")
            }
            return text
        }
    }

    override suspend fun ping() {
        post("/chat/findChats/$instancePath", JsonObject())
    }

    override suspend fun listChats(limit: Int): List<Chat> {
        val raw = Http.parse(post("/chat/findChats/$instancePath", JsonObject()))
        val names = loadNames()
        val chats = asArray(raw).mapNotNull { mapChat(it, names) }
            .sortedByDescending { epoch(it.lastMessageDate) }
        return chats.take(limit)
    }

    private fun loadNames(): Map<String, String> {
        nameCache?.let { return it }
        val map = HashMap<String, String>()
        runCatching {
            val body = JsonObject().apply { add("where", JsonObject()) }
            val raw = Http.parse(post("/chat/findContacts/$instancePath", body))
            for (c in asArray(raw)) {
                val jid = c.str("remoteJid").ifBlank { c.str("id") }
                val name = c.str("pushName")
                if (jid.isNotBlank() && name.isNotBlank()) map[jid] = name
            }
        }
        nameCache = map
        return map
    }

    override suspend fun listMessages(chatId: String, limit: Int): List<Message> {
        val body = JsonObject().apply {
            add("where", JsonObject().apply {
                add("key", JsonObject().apply { addProperty("remoteJid", toJid(chatId)) })
            })
            addProperty("page", 1)
            addProperty("offset", minOf(limit * 2, 400))
        }
        val raw = Http.parse(post("/chat/findMessages/$instancePath", body))
        return parseMessagesResponse(raw)
            .map { mapMessage(it) }
            .filter { isMeaningful(it) }
            .sortedByDescending { epoch(it.date) }
            .take(limit)
            .reversed() // oldest first for chronological display
    }

    override suspend fun sendText(chatId: String, text: String, replyToId: String, replyFromMe: Boolean) {
        val body = JsonObject().apply {
            addProperty("number", toJid(chatId))
            addProperty("text", text)
            quoted(replyToId, replyFromMe, toJid(chatId))?.let { add("quoted", it) }
        }
        post("/message/sendText/$instancePath", body)
    }

    override suspend fun sendVoice(chatId: String, wav: ByteArray, durationSec: Int, replyToId: String, replyFromMe: Boolean) {
        val body = JsonObject().apply {
            addProperty("number", toJid(chatId))
            addProperty("audio", Base64.encodeToString(wav, Base64.NO_WRAP))
            addProperty("encoding", true)
            quoted(replyToId, replyFromMe, toJid(chatId))?.let { add("quoted", it) }
        }
        post("/message/sendWhatsAppAudio/$instancePath", body)
    }

    override suspend fun sendReaction(chatId: String, messageId: String, emoji: String, fromMe: Boolean) {
        val body = JsonObject().apply {
            add("key", JsonObject().apply {
                addProperty("remoteJid", toJid(chatId))
                addProperty("fromMe", fromMe)
                addProperty("id", messageId)
            })
            addProperty("reaction", emoji)
        }
        post("/message/sendReaction/$instancePath", body)
    }

    override suspend fun fetchMedia(chatId: String, message: Message): ByteArray? = runCatching {
        val res = Http.parse(
            post("/chat/getBase64FromMediaMessage/$instancePath", JsonObject().apply {
                add("message", JsonObject().apply {
                    add("key", JsonObject().apply {
                        addProperty("id", message.id)
                        addProperty("remoteJid", toJid(chatId))
                        addProperty("fromMe", message.isOutgoing)
                    })
                })
                addProperty("convertToMp4", false)
            }),
        ).obj()
        val b64 = res.str("base64")
        if (b64.isNotBlank()) Base64.decode(b64, Base64.NO_WRAP) else null
    }.getOrNull()

    private fun quoted(id: String, fromMe: Boolean, jid: String): JsonObject? {
        if (id.isBlank()) return null
        return JsonObject().apply {
            add("key", JsonObject().apply {
                addProperty("remoteJid", jid)
                addProperty("fromMe", fromMe)
                addProperty("id", id)
            })
        }
    }

    override suspend fun markAsRead(chatId: String, messages: List<Message>) {
        val remoteJid = toJid(chatId)
        val read = JsonArray()
        messages.filter { !it.isOutgoing && it.id.isNotBlank() }.take(30).forEach { m ->
            read.add(JsonObject().apply {
                addProperty("remoteJid", remoteJid)
                addProperty("fromMe", false)
                addProperty("id", m.id)
            })
        }
        if (read.size() == 0) return
        post("/chat/markMessageAsRead/$instancePath", JsonObject().apply { add("readMessages", read) })
    }

    /* ---------------- mapping ---------------- */

    private fun mapChat(c: JsonObject, names: Map<String, String>): Chat? {
        val jid = c.str("remoteJid").ifBlank { c.str("id") }
        if (jid.isBlank() || !jid.contains("@") || jid == "status@broadcast") return null
        val type = when {
            jid.endsWith("@g.us") -> ChatType.GROUP
            jid.endsWith("@newsletter") -> ChatType.CHANNEL
            else -> ChatType.USER
        }
        val lastMessage = c.optObj("lastMessage")
        val lastDate = toIso(c.get("lastMsgTimestamp"))
            ?: toIso(lastMessage?.get("messageTimestamp"))
            ?: toIso(c.get("updatedAt"))
        val isUser = type == ChatType.USER
        val lastKey = lastMessage.optObj("key")
        val contactName = if (isUser) names[jid].orEmpty() else ""
        val lastSenderName = if (isUser && lastKey?.get("fromMe")?.asBooleanOrFalse() != true) {
            lastMessage.str("pushName")
        } else ""
        val name = firstNonBlank(
            c.str("name"), c.str("pushName"), c.str("subject"),
            contactName, lastSenderName, fallbackName(jid, type),
        )
        val lastContent = lastMessage.optObj("message")
        val preview = previewText(textOf(lastContent).ifBlank { mediaTag(lastContent, lastMessage.str("messageType")).orEmpty() })
        return Chat(
            channel = ChannelKind.WHATSAPP,
            boxId = boxId,
            id = jid,
            name = name,
            type = type,
            unreadCount = (c.intOrNull("unreadCount") ?: c.intOrNull("unreadMessages") ?: 0),
            lastMessageDate = lastDate,
            lastMessagePreview = preview,
        )
    }

    private fun mapMessage(r: JsonObject): Message {
        val key = r.optObj("key")
        val message = r.optObj("message")
        val isOutgoing = key?.get("fromMe")?.asBooleanOrFalse() ?: r.get("fromMe")?.asBooleanOrFalse() ?: false
        return Message(
            id = key.str("id").ifBlank { r.str("id") },
            text = textOf(message),
            media = mediaTag(message, r.str("messageType")),
            date = toIso(r.get("messageTimestamp")),
            isOutgoing = isOutgoing,
            senderName = if (isOutgoing) "" else r.str("pushName"),
            durationSec = message.optObj("audioMessage").intOrNull("seconds") ?: 0,
            fileName = fileNameOf(message),
        )
    }

    private fun isMeaningful(m: Message): Boolean {
        if (m.media == "[reaction]") return false
        return m.text.trim().isNotEmpty() || m.media != null
    }

    private companion object {
        fun asArray(el: com.google.gson.JsonElement?): List<JsonObject> {
            el.arr()?.let { return it.mapNotNull { e -> e as? JsonObject } }
            val obj = el.obj() ?: return emptyList()
            for (k in listOf("chats", "records", "data")) {
                obj.optArr(k)?.let { return it.mapNotNull { e -> e as? JsonObject } }
            }
            return emptyList()
        }

        fun parseMessagesResponse(el: com.google.gson.JsonElement?): List<JsonObject> {
            el.arr()?.let { return it.mapNotNull { e -> e as? JsonObject } }
            val obj = el.obj() ?: return emptyList()
            obj.optArr("messages")?.let { return it.mapNotNull { e -> e as? JsonObject } }
            obj.optObj("messages")?.optArr("records")?.let { return it.mapNotNull { e -> e as? JsonObject } }
            obj.optArr("records")?.let { return it.mapNotNull { e -> e as? JsonObject } }
            return emptyList()
        }

        fun toJid(idOrPhone: String): String {
            if (idOrPhone.contains("@")) return idOrPhone
            val digits = idOrPhone.filter { it.isDigit() }
            return "$digits@s.whatsapp.net"
        }

        fun jidUser(jid: String): String = jid.substringBefore("@").substringBefore(":")

        fun fallbackName(jid: String, type: ChatType): String {
            val user = jidUser(jid)
            return when {
                type == ChatType.GROUP -> "Grupo ${user.takeLast(6)}"
                type == ChatType.CHANNEL -> "Canal ${user.takeLast(6)}"
                jid.endsWith("@lid") -> "Contato ${user.takeLast(6)}"
                else -> "+$user"
            }
        }

        fun textOf(message: JsonObject?): String {
            message ?: return ""
            message.get("conversation")?.let { if (it.isJsonPrimitive) return it.asString }
            message.optObj("extendedTextMessage")?.let { if (it.get("text")?.isJsonPrimitive == true) return it.str("text") }
            for (k in listOf("imageMessage", "videoMessage", "documentMessage")) {
                val cap = message.optObj(k).str("caption")
                if (cap.isNotBlank()) return cap
            }
            return ""
        }

        fun fileNameOf(message: JsonObject?): String {
            message ?: return ""
            message.optObj("documentMessage")?.let { val n = it.str("fileName"); if (n.isNotBlank()) return n }
            message.optObj("documentWithCaptionMessage")?.optObj("message")?.optObj("documentMessage")
                ?.let { val n = it.str("fileName"); if (n.isNotBlank()) return n }
            return ""
        }

        fun mediaTag(message: JsonObject?, messageType: String): String? {
            message ?: return null
            fun has(k: String) = message.get(k) != null || messageType == k
            return when {
                has("audioMessage") -> if (message.optObj("audioMessage")?.get("ptt")?.asBooleanOrFalse() == false) "[audio]" else "[voice]"
                has("imageMessage") -> "[photo]"
                has("videoMessage") -> "[video]"
                has("stickerMessage") -> "[sticker]"
                has("documentMessage") || has("documentWithCaptionMessage") -> "[file]"
                has("locationMessage") -> "[location]"
                has("contactMessage") || has("contactsArrayMessage") -> "[contact]"
                has("pollCreationMessage") || has("pollCreationMessageV3") -> "[poll]"
                has("reactionMessage") -> "[reaction]"
                else -> null
            }
        }

        fun toIso(v: com.google.gson.JsonElement?): String? {
            v ?: return null
            if (!v.isJsonPrimitive) return null
            val prim = v.asJsonPrimitive
            val ms: Long = when {
                prim.isNumber -> {
                    val n = prim.asLong
                    if (n < 1_000_000_000_000L) n * 1000 else n
                }
                prim.isString && prim.asString.matches(Regex("\\d+")) -> {
                    val n = prim.asString.toLong()
                    if (n < 1_000_000_000_000L) n * 1000 else n
                }
                prim.isString -> return runCatching { Instant.parse(prim.asString).toString() }.getOrNull()
                else -> return null
            }
            return Instant.ofEpochMilli(ms).toString()
        }

        fun epoch(iso: String?): Long {
            iso ?: return 0
            return runCatching { Instant.parse(iso).toEpochMilli() }.getOrDefault(0)
        }

        fun firstNonBlank(vararg values: String): String = values.firstOrNull { it.isNotBlank() } ?: ""

        fun previewText(s: String): String = s.replace(Regex("\\s+"), " ").trim().take(120)

        fun com.google.gson.JsonElement.asBooleanOrFalse(): Boolean =
            runCatching { if (isJsonPrimitive) asBoolean else false }.getOrDefault(false)
    }
}
