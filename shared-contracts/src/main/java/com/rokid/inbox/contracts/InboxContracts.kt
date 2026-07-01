package com.rokid.inbox.contracts

/* ---------------- runtime / handshake ---------------- */

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
}

data class DeviceStatus(
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val statusLabel: String = "Waiting for the phone runtime.",
    val bluetoothClientCount: Int = 0,
    val lastError: String? = null,
)

data class ProtocolHello(
    val protocolVersion: Int = TransportConstants.PROTOCOL_VERSION,
    val appVersion: String = "",
    val capabilities: List<String> = emptyList(),
)

data class ProtocolHelloAck(
    val protocolVersion: Int = TransportConstants.PROTOCOL_VERSION,
    val appVersion: String = "",
    val capabilities: List<String> = emptyList(),
)

/* ---------------- inbox domain (ported from even-inbox types.ts) ---------------- */

enum class ChannelKind {
    WHATSAPP,
    TELEGRAM,
    GMAIL,
    GITHUB,
}

enum class ChatType {
    USER,
    GROUP,
    CHANNEL,
}

/** A single conversation across any channel. `boxLabel` (e.g. "[W]" / "[W1]") is
 *  precomputed on the phone so the glasses stay channel-agnostic. */
data class Chat(
    val channel: ChannelKind = ChannelKind.WHATSAPP,
    val boxId: String = "",
    val id: String = "",
    val name: String = "",
    val type: ChatType = ChatType.USER,
    val unreadCount: Int = 0,
    val lastMessageDate: String? = null,
    val boxLabel: String = "",
)

data class Message(
    val id: String = "",
    val text: String = "",
    val media: String? = null,
    val date: String? = null,
    val isOutgoing: Boolean = false,
    val senderName: String = "",
    /** Duration in seconds for voice/audio messages (0 otherwise). */
    val durationSec: Int = 0,
) {
    val isPlayableAudio: Boolean get() = media == "[voice]" || media == "[audio]"
}

/** Voice capture intent: reply to the open chat, or search chats by name. */
enum class VoiceMode {
    REPLY,
    SEARCH,
}

/** After transcription, send the transcribed text or the original audio note. */
enum class SendMode {
    TEXT,
    AUDIO,
}

/** A canned reply configured on the phone and sent from the glasses. */
data class QuickMessage(
    val title: String = "",
    val body: String = "",
)

/* ---------------- wire messages ---------------- */

sealed interface GlassesToPhoneMessage {
    data class Hello(val hello: ProtocolHello) : GlassesToPhoneMessage
    data class RequestInbox(val filter: String = "all") : GlassesToPhoneMessage
    data class OpenChat(val boxId: String, val chatId: String, val limit: Int = 20) : GlassesToPhoneMessage
    data class LoadOlder(val boxId: String, val chatId: String, val limit: Int) : GlassesToPhoneMessage
    data class MarkRead(val boxId: String, val chatId: String) : GlassesToPhoneMessage
    data class SendText(
        val boxId: String,
        val chatId: String,
        val text: String,
        val replyToId: String = "",
        val replyFromMe: Boolean = false,
    ) : GlassesToPhoneMessage
    data class StartVoice(
        val mode: VoiceMode,
        val boxId: String = "",
        val chatId: String = "",
        val replyToId: String = "",
        val replyFromMe: Boolean = false,
    ) : GlassesToPhoneMessage
    data class AudioChunk(val base64: String) : GlassesToPhoneMessage
    data object EndVoice : GlassesToPhoneMessage
    data class ConfirmSend(val mode: SendMode) : GlassesToPhoneMessage
    data object CancelVoice : GlassesToPhoneMessage
    data object RequestQuick : GlassesToPhoneMessage
    data class SendReaction(
        val boxId: String,
        val chatId: String,
        val messageId: String,
        val emoji: String,
        val fromMe: Boolean,
    ) : GlassesToPhoneMessage
    data class PlayAudio(
        val boxId: String,
        val chatId: String,
        val messageId: String,
        val fromMe: Boolean = false,
    ) : GlassesToPhoneMessage
    data class RequestImage(
        val boxId: String,
        val chatId: String,
        val messageId: String,
        val fromMe: Boolean = false,
    ) : GlassesToPhoneMessage
}

sealed interface PhoneToGlassesMessage {
    data class HelloAck(val ack: ProtocolHelloAck) : PhoneToGlassesMessage
    data class Status(val status: DeviceStatus) : PhoneToGlassesMessage
    data class Error(val message: String) : PhoneToGlassesMessage
    data class InboxSnapshot(
        val chats: List<Chat> = emptyList(),
        val filter: String = "all",
    ) : PhoneToGlassesMessage
    data class ChatSnapshot(
        val boxId: String = "",
        val chatId: String = "",
        val chatName: String = "",
        val canSend: Boolean = false,
        val messages: List<Message> = emptyList(),
        val atStart: Boolean = true,
    ) : PhoneToGlassesMessage
    data class SearchResults(
        val query: String = "",
        val chats: List<Chat> = emptyList(),
    ) : PhoneToGlassesMessage
    /** Result of an on-phone Whisper transcription of the streamed voice note. */
    data class Transcription(val text: String = "", val durationSec: Int = 0) : PhoneToGlassesMessage
    data class SendResult(
        val ok: Boolean = false,
        val mode: SendMode = SendMode.TEXT,
        val error: String? = null,
    ) : PhoneToGlassesMessage
    data class QuickMessages(val items: List<QuickMessage> = emptyList()) : PhoneToGlassesMessage
    /** Transient feedback for a message-level action (react / play). */
    data class ActionResult(val ok: Boolean = false, val text: String = "") : PhoneToGlassesMessage
    /** Downscaled image bytes (JPEG, base64) for an on-glasses photo view. */
    data class ImageResult(
        val messageId: String = "",
        val ok: Boolean = false,
        val base64: String = "",
        val error: String? = null,
    ) : PhoneToGlassesMessage
}

object ErrorMessages {
    const val INVALID_PHONE_MESSAGE = "Invalid phone message"
    const val INVALID_GLASSES_MESSAGE = "Invalid glasses message"
}
