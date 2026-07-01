package com.rokid.inbox.contracts

import com.google.gson.Gson

data class WireEnvelope(
    val channel: String,
    val type: String,
    val payloadJson: String? = null,
)

/**
 * JSON envelope protocol shared by phone and glasses. The `runtime` channel
 * carries the handshake + connection status; the `inbox` channel carries all
 * application messages (chats, messages, voice streaming, transcription, send).
 */
object WireProtocol {
    private val gson = Gson()

    fun encodeGlassesMessage(message: GlassesToPhoneMessage): String =
        gson.toJson(glassesEnvelopeFor(message))

    fun decodeGlassesMessageOrNull(json: String): GlassesToPhoneMessage? =
        runCatching { gson.fromJson(json, WireEnvelope::class.java) }
            .getOrNull()
            ?.let(::glassesMessageFor)

    fun encodePhoneMessage(message: PhoneToGlassesMessage): String =
        gson.toJson(phoneEnvelopeFor(message))

    fun decodePhoneMessageOrNull(json: String): PhoneToGlassesMessage? =
        runCatching { gson.fromJson(json, WireEnvelope::class.java) }
            .getOrNull()
            ?.let(::phoneMessageFor)

    /* ---------------- glasses -> phone ---------------- */

    private fun glassesEnvelopeFor(message: GlassesToPhoneMessage): WireEnvelope = when (message) {
        is GlassesToPhoneMessage.Hello -> WireEnvelope("runtime", "hello", gson.toJson(message.hello))
        is GlassesToPhoneMessage.RequestInbox -> WireEnvelope("inbox", "request_inbox", gson.toJson(message))
        is GlassesToPhoneMessage.OpenChat -> WireEnvelope("inbox", "open_chat", gson.toJson(message))
        is GlassesToPhoneMessage.LoadOlder -> WireEnvelope("inbox", "load_older", gson.toJson(message))
        is GlassesToPhoneMessage.MarkRead -> WireEnvelope("inbox", "mark_read", gson.toJson(message))
        is GlassesToPhoneMessage.SendText -> WireEnvelope("inbox", "send_text", gson.toJson(message))
        is GlassesToPhoneMessage.StartVoice -> WireEnvelope("inbox", "start_voice", gson.toJson(message))
        is GlassesToPhoneMessage.AudioChunk -> WireEnvelope("inbox", "audio_chunk", gson.toJson(message))
        GlassesToPhoneMessage.EndVoice -> WireEnvelope("inbox", "end_voice")
        is GlassesToPhoneMessage.ConfirmSend -> WireEnvelope("inbox", "confirm_send", gson.toJson(message))
        GlassesToPhoneMessage.CancelVoice -> WireEnvelope("inbox", "cancel_voice")
        GlassesToPhoneMessage.RequestQuick -> WireEnvelope("inbox", "request_quick")
        is GlassesToPhoneMessage.SendReaction -> WireEnvelope("inbox", "send_reaction", gson.toJson(message))
        is GlassesToPhoneMessage.PlayAudio -> WireEnvelope("inbox", "play_audio", gson.toJson(message))
        is GlassesToPhoneMessage.RequestImage -> WireEnvelope("inbox", "request_image", gson.toJson(message))
        is GlassesToPhoneMessage.RequestDescription -> WireEnvelope("inbox", "request_description", gson.toJson(message))
    }

    private fun glassesMessageFor(envelope: WireEnvelope): GlassesToPhoneMessage? = when (envelope.channel) {
        "runtime" -> when (envelope.type) {
            "hello" -> parse(envelope, ProtocolHello::class.java)?.let { GlassesToPhoneMessage.Hello(it) }
            else -> null
        }

        "inbox" -> when (envelope.type) {
            "request_inbox" -> parse(envelope, GlassesToPhoneMessage.RequestInbox::class.java)
            "open_chat" -> parse(envelope, GlassesToPhoneMessage.OpenChat::class.java)
            "load_older" -> parse(envelope, GlassesToPhoneMessage.LoadOlder::class.java)
            "mark_read" -> parse(envelope, GlassesToPhoneMessage.MarkRead::class.java)
            "send_text" -> parse(envelope, GlassesToPhoneMessage.SendText::class.java)
            "start_voice" -> parse(envelope, GlassesToPhoneMessage.StartVoice::class.java)
            "audio_chunk" -> parse(envelope, GlassesToPhoneMessage.AudioChunk::class.java)
            "end_voice" -> GlassesToPhoneMessage.EndVoice
            "confirm_send" -> parse(envelope, GlassesToPhoneMessage.ConfirmSend::class.java)
            "cancel_voice" -> GlassesToPhoneMessage.CancelVoice
            "request_quick" -> GlassesToPhoneMessage.RequestQuick
            "send_reaction" -> parse(envelope, GlassesToPhoneMessage.SendReaction::class.java)
            "play_audio" -> parse(envelope, GlassesToPhoneMessage.PlayAudio::class.java)
            "request_image" -> parse(envelope, GlassesToPhoneMessage.RequestImage::class.java)
            "request_description" -> parse(envelope, GlassesToPhoneMessage.RequestDescription::class.java)
            else -> null
        }

        else -> null
    }

    /* ---------------- phone -> glasses ---------------- */

    private fun phoneEnvelopeFor(message: PhoneToGlassesMessage): WireEnvelope = when (message) {
        is PhoneToGlassesMessage.HelloAck -> WireEnvelope("runtime", "hello_ack", gson.toJson(message.ack))
        is PhoneToGlassesMessage.Status -> WireEnvelope("runtime", "status", gson.toJson(message.status))
        is PhoneToGlassesMessage.SetLocale -> WireEnvelope("runtime", "set_locale", gson.toJson(message))
        is PhoneToGlassesMessage.Error -> WireEnvelope("runtime", "error", gson.toJson(message))
        is PhoneToGlassesMessage.InboxSnapshot -> WireEnvelope("inbox", "inbox_snapshot", gson.toJson(message))
        is PhoneToGlassesMessage.ChatSnapshot -> WireEnvelope("inbox", "chat_snapshot", gson.toJson(message))
        is PhoneToGlassesMessage.SearchResults -> WireEnvelope("inbox", "search_results", gson.toJson(message))
        is PhoneToGlassesMessage.Transcription -> WireEnvelope("inbox", "transcription", gson.toJson(message))
        is PhoneToGlassesMessage.SendResult -> WireEnvelope("inbox", "send_result", gson.toJson(message))
        is PhoneToGlassesMessage.QuickMessages -> WireEnvelope("inbox", "quick_messages", gson.toJson(message))
        is PhoneToGlassesMessage.ActionResult -> WireEnvelope("inbox", "action_result", gson.toJson(message))
        is PhoneToGlassesMessage.ImageResult -> WireEnvelope("inbox", "image_result", gson.toJson(message))
        is PhoneToGlassesMessage.DescriptionResult -> WireEnvelope("inbox", "description_result", gson.toJson(message))
    }

    private fun phoneMessageFor(envelope: WireEnvelope): PhoneToGlassesMessage? = when (envelope.channel) {
        "runtime" -> when (envelope.type) {
            "hello_ack" -> parse(envelope, ProtocolHelloAck::class.java)?.let { PhoneToGlassesMessage.HelloAck(it) }
            "status" -> parse(envelope, DeviceStatus::class.java)?.let { PhoneToGlassesMessage.Status(it) }
            "set_locale" -> parse(envelope, PhoneToGlassesMessage.SetLocale::class.java)
            "error" -> parse(envelope, PhoneToGlassesMessage.Error::class.java)
            else -> null
        }

        "inbox" -> when (envelope.type) {
            "inbox_snapshot" -> parse(envelope, PhoneToGlassesMessage.InboxSnapshot::class.java)
            "chat_snapshot" -> parse(envelope, PhoneToGlassesMessage.ChatSnapshot::class.java)
            "search_results" -> parse(envelope, PhoneToGlassesMessage.SearchResults::class.java)
            "transcription" -> parse(envelope, PhoneToGlassesMessage.Transcription::class.java)
            "send_result" -> parse(envelope, PhoneToGlassesMessage.SendResult::class.java)
            "quick_messages" -> parse(envelope, PhoneToGlassesMessage.QuickMessages::class.java)
            "action_result" -> parse(envelope, PhoneToGlassesMessage.ActionResult::class.java)
            "image_result" -> parse(envelope, PhoneToGlassesMessage.ImageResult::class.java)
            "description_result" -> parse(envelope, PhoneToGlassesMessage.DescriptionResult::class.java)
            else -> null
        }

        else -> null
    }

    private fun <T> parse(envelope: WireEnvelope, clazz: Class<T>): T? {
        val payload = envelope.payloadJson ?: return null
        return runCatching { gson.fromJson(payload, clazz) }.getOrNull()
    }
}
