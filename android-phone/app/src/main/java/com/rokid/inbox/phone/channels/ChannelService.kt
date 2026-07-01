package com.rokid.inbox.phone.channels

import com.rokid.inbox.contracts.Chat
import com.rokid.inbox.contracts.Message

/**
 * Common contract every channel (WhatsApp, Telegram, Gmail, GitHub) implements
 * so the unified inbox and glasses HUD stay channel-agnostic. Ported from
 * even-inbox `src/channels/types.ts`.
 */
interface ChannelService {
    val kind: com.rokid.inbox.contracts.ChannelKind
    val boxId: String
    /** false = read-only (Gmail, GitHub): the glasses hide the reply menu. */
    val canSend: Boolean
    /** Whether emoji reactions can be sent (WhatsApp, Telegram). */
    val canReact: Boolean get() = false

    /** Messages are returned oldest-first (chronological, oldest at top). */
    suspend fun listChats(limit: Int = 20): List<Chat>
    suspend fun listMessages(chatId: String, limit: Int = 20): List<Message>

    /** `replyToId` quotes a specific message when non-blank. */
    suspend fun sendText(chatId: String, text: String, replyToId: String = "", replyFromMe: Boolean = false)
    suspend fun sendVoice(chatId: String, wav: ByteArray, durationSec: Int, replyToId: String = "", replyFromMe: Boolean = false)
    suspend fun sendReaction(chatId: String, messageId: String, emoji: String, fromMe: Boolean) {
        throw UnsupportedOperationException("Reactions not supported")
    }
    suspend fun markAsRead(chatId: String, messages: List<Message>)

    /** Download the raw media bytes of a message (voice/audio or image), or null. */
    suspend fun fetchMedia(chatId: String, message: Message): ByteArray? = null

    /** Lightweight credential/connectivity probe used by the settings screen. */
    suspend fun ping()
    suspend fun disconnect() {}
}
