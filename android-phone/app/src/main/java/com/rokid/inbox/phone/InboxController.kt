package com.rokid.inbox.phone

import android.util.Base64
import android.util.Log
import com.rokid.inbox.contracts.Chat
import com.rokid.inbox.contracts.ChannelKind
import com.rokid.inbox.contracts.GlassesToPhoneMessage
import com.rokid.inbox.contracts.Message
import com.rokid.inbox.contracts.PhoneToGlassesMessage
import com.rokid.inbox.contracts.SendMode
import com.rokid.inbox.contracts.VoiceMode
import com.rokid.inbox.phone.channels.ChannelService
import com.rokid.inbox.phone.channels.InboxAggregator
import com.rokid.inbox.phone.voice.Wav
import com.rokid.inbox.phone.voice.WhisperClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * The phone-side brain. Handles every glasses request over the transport:
 * unified inbox, conversation reads, voice capture -> Whisper -> preview, and
 * text/voice sends. Mirrors the app logic of even-inbox `src/main.ts`, split
 * across the wire.
 */
class InboxController(
    private val servicesProvider: () -> List<ChannelService>,
    private val whisperProvider: () -> WhisperClient,
    private val quickProvider: () -> List<com.rokid.inbox.contracts.QuickMessage>,
    private val scope: CoroutineScope,
) {
    @Volatile var sender: (PhoneToGlassesMessage) -> Unit = {}

    private var lastChats: List<Chat> = emptyList()

    // voice session
    private val pcm = ByteArrayOutputStream()
    private var voiceMode: VoiceMode = VoiceMode.REPLY
    private var voiceBoxId: String = ""
    private var voiceChatId: String = ""
    private var voiceReplyToId: String = ""
    private var voiceReplyFromMe: Boolean = false
    private var pendingWav: ByteArray? = null
    private var pendingDuration: Int = 0
    private var pendingTranscription: String = ""
    private var mediaPlayer: android.media.MediaPlayer? = null

    fun onClientConnected() {
        scope.launch(Dispatchers.IO) { sendInbox("all") }
    }

    fun handle(message: GlassesToPhoneMessage) {
        when (message) {
            is GlassesToPhoneMessage.Hello -> Unit // handled by transport handshake
            is GlassesToPhoneMessage.RequestInbox -> scope.launch(Dispatchers.IO) { sendInbox(message.filter) }
            is GlassesToPhoneMessage.OpenChat -> scope.launch(Dispatchers.IO) {
                openChat(message.boxId, message.chatId, message.limit, atStartAllowed = true)
            }
            is GlassesToPhoneMessage.LoadOlder -> scope.launch(Dispatchers.IO) {
                openChat(message.boxId, message.chatId, message.limit, atStartAllowed = true)
            }
            is GlassesToPhoneMessage.MarkRead -> scope.launch(Dispatchers.IO) { markRead(message.boxId, message.chatId) }
            is GlassesToPhoneMessage.SendText -> scope.launch(Dispatchers.IO) {
                sendText(message.boxId, message.chatId, message.text, message.replyToId, message.replyFromMe)
            }
            is GlassesToPhoneMessage.StartVoice -> startVoice(message.mode, message.boxId, message.chatId, message.replyToId, message.replyFromMe)
            is GlassesToPhoneMessage.AudioChunk -> appendAudio(message.base64)
            GlassesToPhoneMessage.EndVoice -> scope.launch(Dispatchers.IO) { endVoice() }
            is GlassesToPhoneMessage.ConfirmSend -> scope.launch(Dispatchers.IO) { confirmSend(message.mode) }
            GlassesToPhoneMessage.CancelVoice -> resetVoice()
            GlassesToPhoneMessage.RequestQuick -> sender(PhoneToGlassesMessage.QuickMessages(quickProvider()))
            is GlassesToPhoneMessage.SendReaction -> scope.launch(Dispatchers.IO) {
                react(message.boxId, message.chatId, message.messageId, message.emoji, message.fromMe)
            }
            is GlassesToPhoneMessage.PlayAudio -> scope.launch(Dispatchers.IO) {
                playAudio(message.boxId, message.chatId, message.messageId, message.fromMe)
            }
            is GlassesToPhoneMessage.RequestImage -> scope.launch(Dispatchers.IO) {
                fetchImage(message.boxId, message.chatId, message.messageId, message.fromMe)
            }
        }
    }

    /* ---------------- inbox ---------------- */

    private suspend fun sendInbox(filter: String) {
        val services = servicesProvider()
        val labels = computeBoxLabels(services)
        val all = try {
            InboxAggregator.fetchUnifiedInbox(services, MAX_CHATS)
        } catch (e: Exception) {
            Log.e(TAG, "fetch inbox failed", e)
            emptyList()
        }
        val labeled = all.map { it.copy(boxLabel = labels[it.boxId].orEmpty()) }
        lastChats = labeled
        val filtered = when (filter) {
            "all" -> labeled
            "unread" -> labeled.filter { it.unreadCount > 0 }
            else -> labeled.filter { it.boxId == filter }
        }
        sender(PhoneToGlassesMessage.InboxSnapshot(chats = filtered, filter = filter))
    }

    private suspend fun openChat(boxId: String, chatId: String, limit: Int, atStartAllowed: Boolean) {
        val svc = serviceFor(boxId) ?: run {
            sender(PhoneToGlassesMessage.Error("Caixa não conectada."))
            return
        }
        val chat = lastChats.firstOrNull { it.boxId == boxId && it.id == chatId }
        try {
            val messages = svc.listMessages(chatId, limit)
            sender(
                PhoneToGlassesMessage.ChatSnapshot(
                    boxId = boxId,
                    chatId = chatId,
                    chatName = chat?.name.orEmpty(),
                    canSend = svc.canSend,
                    messages = messages,
                    atStart = messages.size < limit,
                ),
            )
            if (chat != null && chat.unreadCount > 0) {
                runCatching { svc.markAsRead(chatId, messages) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "openChat failed", e)
            sender(PhoneToGlassesMessage.Error("Erro ao carregar mensagens: ${e.message.orEmpty().take(120)}"))
        }
    }

    private suspend fun markRead(boxId: String, chatId: String) {
        val svc = serviceFor(boxId) ?: return
        runCatching { svc.markAsRead(chatId, emptyList()) }
    }

    private suspend fun sendText(boxId: String, chatId: String, text: String, replyToId: String, replyFromMe: Boolean) {
        val svc = serviceFor(boxId)
        if (svc == null || !svc.canSend) {
            sender(PhoneToGlassesMessage.SendResult(ok = false, mode = SendMode.TEXT, error = "Canal somente leitura."))
            return
        }
        try {
            svc.sendText(chatId, text, replyToId, replyFromMe)
            sender(PhoneToGlassesMessage.SendResult(ok = true, mode = SendMode.TEXT))
        } catch (e: Exception) {
            sender(PhoneToGlassesMessage.SendResult(ok = false, mode = SendMode.TEXT, error = e.message?.take(160)))
        }
    }

    private suspend fun react(boxId: String, chatId: String, messageId: String, emoji: String, fromMe: Boolean) {
        val svc = serviceFor(boxId)
        if (svc == null || !svc.canReact) {
            sender(PhoneToGlassesMessage.ActionResult(ok = false, text = "Reacoes nao suportadas."))
            return
        }
        try {
            svc.sendReaction(chatId, messageId, emoji, fromMe)
            sender(PhoneToGlassesMessage.ActionResult(ok = true, text = "Reagiu com $emoji"))
        } catch (e: Exception) {
            sender(PhoneToGlassesMessage.ActionResult(ok = false, text = "Erro ao reagir: ${e.message.orEmpty().take(120)}"))
        }
    }

    private suspend fun playAudio(boxId: String, chatId: String, messageId: String, fromMe: Boolean) {
        val svc = serviceFor(boxId) ?: return
        try {
            val bytes = svc.fetchMedia(chatId, Message(id = messageId, isOutgoing = fromMe))
            if (bytes == null || bytes.isEmpty()) {
                sender(PhoneToGlassesMessage.ActionResult(ok = false, text = "Audio indisponivel."))
                return
            }
            val file = java.io.File.createTempFile("play", ".ogg")
            file.writeBytes(bytes)
            withContext(Dispatchers.Main) { startPlayback(file) }
            sender(PhoneToGlassesMessage.ActionResult(ok = true, text = "Tocando audio no telefone..."))
        } catch (e: Exception) {
            sender(PhoneToGlassesMessage.ActionResult(ok = false, text = "Erro ao tocar: ${e.message.orEmpty().take(120)}"))
        }
    }

    private suspend fun fetchImage(boxId: String, chatId: String, messageId: String, fromMe: Boolean) {
        val svc = serviceFor(boxId) ?: return
        try {
            val bytes = svc.fetchMedia(chatId, Message(id = messageId, isOutgoing = fromMe))
            if (bytes == null || bytes.isEmpty()) {
                sender(PhoneToGlassesMessage.ImageResult(messageId = messageId, ok = false, error = "Imagem indisponivel"))
                return
            }
            val small = downscaleJpeg(bytes)
            sender(
                PhoneToGlassesMessage.ImageResult(
                    messageId = messageId,
                    ok = true,
                    base64 = Base64.encodeToString(small, Base64.NO_WRAP),
                ),
            )
        } catch (e: Exception) {
            sender(PhoneToGlassesMessage.ImageResult(messageId = messageId, ok = false, error = e.message?.take(120)))
        }
    }

    /** Downscale to keep the transfer small for the glasses display. */
    private fun downscaleJpeg(bytes: ByteArray, maxDim: Int = 480, quality: Int = 75): ByteArray {
        val src = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return bytes
        val scale = minOf(1f, maxDim.toFloat() / maxOf(src.width, src.height))
        val out = ByteArrayOutputStream()
        val scaled = if (scale < 1f) {
            android.graphics.Bitmap.createScaledBitmap(
                src,
                (src.width * scale).toInt().coerceAtLeast(1),
                (src.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            src
        }
        scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }

    private fun startPlayback(file: java.io.File) {
        runCatching { mediaPlayer?.release() }
        mediaPlayer = android.media.MediaPlayer().apply {
            setDataSource(file.absolutePath)
            setOnCompletionListener {
                runCatching { it.release() }
                if (mediaPlayer === this) mediaPlayer = null
                runCatching { file.delete() }
            }
            prepare()
            start()
        }
    }

    /* ---------------- voice ---------------- */

    private fun startVoice(mode: VoiceMode, boxId: String, chatId: String, replyToId: String, replyFromMe: Boolean) {
        synchronized(pcm) {
            pcm.reset()
            voiceMode = mode
            voiceBoxId = boxId
            voiceChatId = chatId
            voiceReplyToId = replyToId
            voiceReplyFromMe = replyFromMe
            pendingWav = null
            pendingDuration = 0
            pendingTranscription = ""
        }
    }

    private fun appendAudio(base64: String) {
        val bytes = runCatching { Base64.decode(base64, Base64.NO_WRAP) }.getOrNull() ?: return
        synchronized(pcm) { pcm.write(bytes) }
    }

    private suspend fun endVoice() {
        val raw = synchronized(pcm) { pcm.toByteArray().also { pcm.reset() } }
        if (raw.isEmpty()) {
            sender(PhoneToGlassesMessage.Error("Nenhum áudio capturado."))
            return
        }
        val duration = maxOf(1, raw.size / (SAMPLE_RATE * 2))
        val wav = Wav.pcmToWav(raw, SAMPLE_RATE)
        pendingWav = wav
        pendingDuration = duration

        val whisper = whisperProvider()
        if (!whisper.isConfigured) {
            // No key: reply channels send the audio note directly; search needs a key.
            if (voiceMode == VoiceMode.REPLY) {
                confirmSend(SendMode.AUDIO)
            } else {
                sender(PhoneToGlassesMessage.Error("Busca por voz precisa da chave OpenAI."))
            }
            return
        }
        val transcription = try {
            whisper.transcribe(wav)
        } catch (e: Exception) {
            Log.e(TAG, "whisper failed", e)
            if (voiceMode == VoiceMode.REPLY) {
                pendingTranscription = ""
                sender(PhoneToGlassesMessage.Transcription(text = "(transcrição falhou)", durationSec = duration))
            } else {
                sender(PhoneToGlassesMessage.Error("Falha na transcrição: ${e.message.orEmpty().take(120)}"))
            }
            return
        }
        if (voiceMode == VoiceMode.SEARCH) {
            val matches = InboxAggregator.searchChatsByName(servicesProvider(), transcription, SEARCH_LIMIT)
            val labels = computeBoxLabels(servicesProvider())
            sender(
                PhoneToGlassesMessage.SearchResults(
                    query = transcription,
                    chats = matches.map { it.copy(boxLabel = labels[it.boxId].orEmpty()) },
                ),
            )
        } else {
            pendingTranscription = transcription
            sender(PhoneToGlassesMessage.Transcription(text = transcription, durationSec = duration))
        }
    }

    private suspend fun confirmSend(mode: SendMode) {
        val svc = serviceFor(voiceBoxId)
        if (svc == null || !svc.canSend) {
            sender(PhoneToGlassesMessage.SendResult(ok = false, mode = mode, error = "Canal somente leitura."))
            return
        }
        try {
            if (mode == SendMode.TEXT) {
                val text = pendingTranscription.trim()
                if (text.isBlank()) throw RuntimeException("Sem texto transcrito disponível")
                svc.sendText(voiceChatId, text, voiceReplyToId, voiceReplyFromMe)
            } else {
                val wav = pendingWav ?: throw RuntimeException("Sem áudio disponível")
                svc.sendVoice(voiceChatId, wav, pendingDuration, voiceReplyToId, voiceReplyFromMe)
            }
            sender(PhoneToGlassesMessage.SendResult(ok = true, mode = mode))
            resetVoice()
        } catch (e: Exception) {
            sender(PhoneToGlassesMessage.SendResult(ok = false, mode = mode, error = e.message?.take(160)))
        }
    }

    private fun resetVoice() {
        synchronized(pcm) {
            pcm.reset()
            pendingWav = null
            pendingDuration = 0
            pendingTranscription = ""
            voiceReplyToId = ""
            voiceReplyFromMe = false
        }
    }

    /* ---------------- helpers ---------------- */

    private fun serviceFor(boxId: String): ChannelService? =
        servicesProvider().firstOrNull { it.boxId == boxId }

    companion object {
        private const val TAG = "InboxController"
        private const val MAX_CHATS = 40
        private const val SEARCH_LIMIT = 200
        private const val SAMPLE_RATE = 16000

        fun glyph(kind: ChannelKind): String = when (kind) {
            ChannelKind.WHATSAPP -> "W"
            ChannelKind.TELEGRAM -> "T"
            ChannelKind.GMAIL -> "E"
            ChannelKind.GITHUB -> "PR"
        }

        /** [W] when a type has one box; [W1]/[W2] when several. */
        fun computeBoxLabels(services: List<ChannelService>): Map<String, String> {
            val byKind = services.groupBy { it.kind }
            val out = HashMap<String, String>()
            for ((kind, list) in byKind) {
                val base = glyph(kind)
                list.forEachIndexed { i, svc ->
                    out[svc.boxId] = if (list.size > 1) "[$base${i + 1}]" else "[$base]"
                }
            }
            return out
        }
    }
}
