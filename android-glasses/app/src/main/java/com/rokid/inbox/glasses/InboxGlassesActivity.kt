package com.rokid.inbox.glasses

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.rokid.inbox.contracts.Chat
import com.rokid.inbox.contracts.ChannelKind
import com.rokid.inbox.contracts.ChatType
import com.rokid.inbox.contracts.GlassesToPhoneMessage
import com.rokid.inbox.contracts.Message
import com.rokid.inbox.contracts.PhoneToGlassesMessage
import com.rokid.inbox.contracts.QuickMessage
import com.rokid.inbox.contracts.SendMode
import com.rokid.inbox.contracts.VoiceMode
import com.rokid.inbox.glasses.audio.MicCapture
import com.rokid.inbox.glasses.transport.HybridBridge
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Glasses HUD app: on-glasses navigation over the unified inbox streamed from the
 * phone. Conversations are a fluid per-message focus list (oldest at top) with
 * per-message actions: reply (quoting), emoji react, and play audio.
 */
class InboxGlassesActivity : AppCompatActivity() {

    private enum class View {
        INBOX, MENU, SEARCH_RECORDING, SEARCH_RESULTS, MESSAGES, MESSAGE_DETAIL, MESSAGE_ACTIONS, REACT,
        REPLY_MENU, QUICK, VOICE_IDLE, RECORDING, TRANSCRIBING, PREVIEW, SENDING, FEEDBACK,
    }

    private lateinit var hud: InboxHudView
    private var bridge: HybridBridge? = null
    private var unsubscribe: (() -> Unit)? = null
    private var mic: MicCapture? = null
    private lateinit var gestures: GestureDetector

    private var view = View.INBOX
    private var statusLabel = "Procurando o telefone via Bluetooth..."
    private var connected = false

    private var chats: List<Chat> = emptyList()
    private var activeFilter = "all"
    private var inboxLoaded = false
    private var inboxLoading = false

    private val ui = Handler(Looper.getMainLooper())
    private var spinnerFrame = 0
    private var spinnerScheduled = false
    private val spinnerRunnable = Runnable {
        spinnerScheduled = false
        if (isLoading()) { spinnerFrame++; render() }
    }

    private var currentChat: Chat? = null
    private var messages: List<Message> = emptyList()
    private var canSend = false
    private var atStart = true
    private var convLoading = false
    private var msgSelected = 0
    private var restoreMessageId = ""
    private var messagesReturn = View.INBOX

    private var searchResults: List<Chat> = emptyList()
    private var searchIdx = 0

    private var pendingTranscription = ""
    private var pendingDuration = 0
    private var feedbackText = ""
    private var feedbackRefetch = false

    private var inboxIdx = 0
    private var menuIdx = 0
    private var previewIdx = 0
    private var replyMenuIdx = 0
    private var quickIdx = 0
    private var actionIdx = 0
    private var reactIdx = 0
    private var quickMessages: List<QuickMessage> = emptyList()
    private var voiceMode = VoiceMode.REPLY

    // Reply target (set when "Responder" is chosen on a specific message).
    private var replyToId = ""
    private var replyFromMe = false

    private var actionItems: List<Pair<String, () -> Unit>> = emptyList()

    // Message detail (expanded reader / photo view).
    private var detailMsgId = ""
    private var detailBitmap: Bitmap? = null
    private var detailImageLoading = false
    private var detailImageError: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_inbox_glasses)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hud = findViewById(R.id.inboxHudView)
        hud.keepScreenOn = true
        gestures = GestureDetector(this, GestureListener())
        requestPermissionsIfNeeded()
        render()
    }

    override fun onStart() {
        super.onStart()
        startBridgeIfReady()
    }

    override fun onStop() {
        unsubscribe?.invoke()
        unsubscribe = null
        spinnerScheduled = false
        ui.removeCallbacks(spinnerRunnable)
        stopMic()
        bridge?.hibernate()
        super.onStop()
    }

    override fun onDestroy() {
        stopMic()
        bridge?.close()
        bridge = null
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMS) startBridgeIfReady()
    }

    private fun startBridgeIfReady() {
        if (!hasBluetoothPermissions()) return
        if (bridge == null) bridge = HybridBridge(applicationContext)
        if (unsubscribe == null) unsubscribe = bridge?.subscribe { msg -> runOnUiThread { onPhoneMessage(msg) } }
        bridge?.resume()
        bridge?.send(GlassesToPhoneMessage.RequestInbox(activeFilter))
    }

    /* ---------------- inbound messages ---------------- */

    private fun onPhoneMessage(message: PhoneToGlassesMessage) {
        when (message) {
            is PhoneToGlassesMessage.HelloAck -> Unit
            is PhoneToGlassesMessage.Status -> {
                statusLabel = message.status.statusLabel
                val nowConnected = message.status.connectionState.name == "CONNECTED"
                val justConnected = nowConnected && !connected
                connected = nowConnected
                if (justConnected) bridge?.send(GlassesToPhoneMessage.RequestInbox(activeFilter))
                if (view == View.INBOX) render()
            }
            is PhoneToGlassesMessage.Error -> {
                feedbackText = message.message
                feedbackRefetch = false
                if (view == View.RECORDING || view == View.TRANSCRIBING || view == View.SEARCH_RECORDING || view == View.SENDING) {
                    view = View.FEEDBACK
                }
                render()
            }
            is PhoneToGlassesMessage.InboxSnapshot -> {
                chats = message.chats
                activeFilter = message.filter
                inboxLoaded = true
                inboxLoading = false
                if (view == View.INBOX) render()
            }
            is PhoneToGlassesMessage.ChatSnapshot -> {
                val chat = currentChat
                if (chat != null && chat.boxId == message.boxId && chat.id == message.chatId) {
                    messages = message.messages
                    canSend = message.canSend
                    atStart = message.atStart
                    convLoading = false
                    msgSelected = when {
                        restoreMessageId.isNotBlank() -> messages.indexOfFirst { it.id == restoreMessageId }.takeIf { it >= 0 }
                            ?: messages.lastIndex
                        else -> messages.lastIndex
                    }.coerceAtLeast(0)
                    restoreMessageId = ""
                    if (view != View.MESSAGES) view = View.MESSAGES
                    render()
                }
            }
            is PhoneToGlassesMessage.SearchResults -> {
                searchResults = message.chats
                searchIdx = 0
                view = View.SEARCH_RESULTS
                render()
            }
            is PhoneToGlassesMessage.Transcription -> {
                if (voiceMode == VoiceMode.REPLY) {
                    pendingTranscription = message.text
                    pendingDuration = message.durationSec
                    previewIdx = 0
                    view = View.PREVIEW
                    render()
                }
            }
            is PhoneToGlassesMessage.SendResult -> {
                feedbackText = if (message.ok) {
                    if (message.mode == SendMode.TEXT) "\u2713 Texto enviado" else "\u2713 Audio enviado"
                } else {
                    "Erro ao enviar: ${message.error.orEmpty().take(120)}"
                }
                feedbackRefetch = message.ok
                view = View.FEEDBACK
                render()
            }
            is PhoneToGlassesMessage.QuickMessages -> {
                quickMessages = message.items
                if (view == View.QUICK) render()
            }
            is PhoneToGlassesMessage.ActionResult -> {
                feedbackText = message.text
                feedbackRefetch = false
                view = View.FEEDBACK
                render()
            }
            is PhoneToGlassesMessage.ImageResult -> {
                if (message.messageId == detailMsgId) {
                    detailImageLoading = false
                    if (message.ok && message.base64.isNotBlank()) {
                        detailBitmap = runCatching {
                            val b = Base64.decode(message.base64, Base64.NO_WRAP)
                            BitmapFactory.decodeByteArray(b, 0, b.size)
                        }.getOrNull()
                        detailImageError = if (detailBitmap == null) "Falha ao decodificar imagem" else null
                    } else {
                        detailBitmap = null
                        detailImageError = message.error ?: "Imagem indisponivel"
                    }
                    if (view == View.MESSAGE_DETAIL) render()
                }
            }
        }
    }

    /* ---------------- rendering ---------------- */

    private fun render() {
        when (view) {
            View.INBOX -> renderInbox()
            View.MENU -> renderMenu()
            View.SEARCH_RECORDING -> hud.renderText(header("Busca"), "\u25CF Gravando...\n\nDiga o nome do contato.", "Tap: buscar - 2x: cancelar")
            View.SEARCH_RESULTS -> renderSearchResults()
            View.MESSAGES -> renderMessages()
            View.MESSAGE_DETAIL -> renderMessageDetail()
            View.MESSAGE_ACTIONS -> hud.renderList(header("acoes"), actionItems.map { it.first }, actionIdx, "Swipe: navegar - Tap: abrir - 2x: voltar")
            View.REACT -> hud.renderList(header("reagir"), EMOJIS.map { "${it.first}  ${it.second}" }, reactIdx, "Swipe: navegar - Tap: reagir - 2x: voltar")
            View.REPLY_MENU -> hud.renderList(header("responder"), listOf("Voz", "Mensagens rapidas"), replyMenuIdx, "Swipe: navegar - Tap: abrir - 2x: voltar")
            View.QUICK -> renderQuick()
            View.VOICE_IDLE -> hud.renderText(header("voz"), "Mensagem de voz\n\nTap para gravar.", "Tap: gravar - 2x: voltar")
            View.RECORDING -> hud.renderText(header("voz"), "\u25CF Gravando...\n\nTap para parar e enviar.", "Tap: parar - 2x: cancelar")
            View.TRANSCRIBING -> renderLoadingScreen("Transcrevendo")
            View.PREVIEW -> renderPreview()
            View.SENDING -> renderLoadingScreen("Enviando")
            View.FEEDBACK -> hud.renderText(header("pronto"), feedbackText, "Tap ou 2x: voltar")
        }
        scheduleSpinner()
    }

    /* ---------------- spinner ---------------- */

    private fun isLoading(): Boolean = when (view) {
        View.TRANSCRIBING, View.SENDING -> true
        View.INBOX -> connected && (inboxLoading || !inboxLoaded)
        View.MESSAGES -> convLoading && messages.isEmpty()
        View.MESSAGE_DETAIL -> detailImageLoading
        else -> false
    }

    private fun spinner(): String = SPINNER[spinnerFrame % SPINNER.size]

    private fun scheduleSpinner() {
        if (isLoading()) {
            if (!spinnerScheduled) {
                spinnerScheduled = true
                ui.postDelayed(spinnerRunnable, 110)
            }
        } else {
            spinnerScheduled = false
            ui.removeCallbacks(spinnerRunnable)
        }
    }

    private fun renderLoadingScreen(label: String) {
        hud.renderText(header(label), "${spinner()}  $label...", "Aguarde...", big = true)
    }

    private fun renderInbox() {
        if (!connected && chats.isEmpty()) {
            hud.renderText(header("conectando"), statusLabel, "2x: sair")
            return
        }
        if (connected && (!inboxLoaded || inboxLoading)) {
            renderLoadingScreen("Carregando")
            return
        }
        val rows = ArrayList<InboxHudView.Row>()
        rows += InboxHudView.Row(0, MENU_ROW)
        chats.forEach { rows += InboxHudView.Row(iconFor(it.channel), chatRowText(it)) }
        inboxIdx = inboxIdx.coerceIn(0, rows.size - 1)
        val ctx = "${filterLabel()} - ${chats.size}"
        hud.renderChatList(header(ctx), rows, inboxIdx, "Swipe: navegar - Tap: abrir - 2x: sair")
    }

    private fun renderMenu() {
        val items = menuItems()
        menuIdx = menuIdx.coerceIn(0, items.size - 1)
        hud.renderChatList(
            header("Menu"),
            items.map { InboxHudView.Row(it.iconRes, it.label) },
            menuIdx,
            "Swipe: navegar - Tap: abrir - 2x: voltar",
        )
    }

    private fun renderSearchResults() {
        if (searchResults.isEmpty()) {
            hud.renderText(header("Busca"), "(nenhum contato encontrado)", "2x: menu")
            return
        }
        searchIdx = searchIdx.coerceIn(0, searchResults.size - 1)
        val rows = searchResults.map { InboxHudView.Row(iconFor(it.channel), chatRowText(it)) }
        hud.renderChatList(header("Busca - ${searchResults.size}"), rows, searchIdx, "Swipe: navegar - Tap: abrir - 2x: menu")
    }

    private fun renderMessages() {
        val chat = currentChat ?: return
        if (messages.isEmpty()) {
            if (convLoading) renderLoadingScreen("Carregando mensagens")
            else hud.renderText(header(chat.name), "(sem mensagens)", if (canSend) "Tap: responder - 2x: voltar" else "2x: voltar")
            return
        }
        val range = computeConvWindow()
        val texts = range.map { messageFull(messages[it]) }
        val selInWin = (msgSelected - range.first).coerceIn(0, texts.size - 1)
        val ctx = "${oneLine(chat.name)}${if (convLoading) " - carregando" else " - ${msgSelected + 1}/${messages.size}"}"
        hud.renderConversation(
            header(ctx),
            texts,
            selInWin,
            olderAbove = range.first > 0,
            newerBelow = range.last < messages.lastIndex,
            hintText = "Swipe: navegar - Tap: abrir - 2x: voltar",
        )
    }

    private fun renderMessageDetail() {
        val m = selectedMessage() ?: run { view = View.MESSAGES; render(); return }
        if (isPhoto(m)) {
            when {
                detailImageLoading -> renderLoadingScreen("Carregando imagem")
                detailBitmap != null -> hud.renderImage(header("foto"), detailBitmap!!, m.text.trim(), "Tap: acoes - 2x: voltar")
                else -> hud.renderText(header("foto"), detailImageError ?: "Imagem indisponivel", "Tap: acoes - 2x: voltar")
            }
        } else {
            hud.renderDetail(
                header(oneLine(currentChat?.name.orEmpty())),
                messageDetailText(m),
                "Swipe: rolar - Tap: acoes - 2x: voltar",
            )
        }
    }

    /** Fill the screen with as many full messages as fit around the selection. */
    private fun computeConvWindow(): IntRange {
        val n = messages.size
        if (n == 0) return IntRange.EMPTY
        val sel = msgSelected.coerceIn(0, n - 1)
        var start = sel
        var end = sel
        var budget = CONV_LINE_BUDGET - convLines(sel)
        var down = true
        while (budget > 0) {
            val canDown = end < n - 1
            val canUp = start > 0
            if (!canDown && !canUp) break
            val goDown = when {
                down && canDown -> true
                !down && canUp -> false
                canDown -> true
                else -> false
            }
            val idx = if (goDown) end + 1 else start - 1
            val l = convLines(idx)
            if (l > budget) {
                val otherIdx = if (goDown) (if (canUp) start - 1 else -1) else (if (canDown) end + 1 else -1)
                if (otherIdx < 0 || convLines(otherIdx) > budget) break
                if (goDown) { start--; budget -= convLines(start) } else { end++; budget -= convLines(end) }
                down = !down
                continue
            }
            if (goDown) end++ else start--
            budget -= l
            down = !down
        }
        return start..end
    }

    private fun convLines(i: Int): Int {
        val len = minOf(messageBody(messages[i]).length, MSG_CHAR_CAP)
        return 1 + maxOf(1, kotlin.math.ceil(len / CONV_CHARS_PER_LINE.toDouble()).toInt())
    }

    private fun renderQuick() {
        if (quickMessages.isEmpty()) {
            hud.renderText(header("rapidas"), "Carregando mensagens rapidas...", "2x: voltar")
            return
        }
        quickIdx = quickIdx.coerceIn(0, quickMessages.size - 1)
        hud.renderList(header("rapidas"), quickMessages.map { it.title }, quickIdx, "Swipe: navegar - Tap: enviar - 2x: voltar")
    }

    private fun renderPreview() {
        val safe = oneLine(pendingTranscription).ifBlank { "(transcricao vazia)" }
        val quoted = if (replyToId.isNotBlank()) "\n(respondendo a mensagem selecionada)" else ""
        hud.renderOptions(
            header("voz"),
            "Transcricao (${pendingDuration}s):\n\"$safe\"$quoted",
            listOf("Enviar texto transcrito", "Enviar audio original"),
            previewIdx,
            "Swipe: escolher - Tap: enviar - 2x: descartar",
        )
    }

    /* ---------------- input ---------------- */

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (gestures.onTouchEvent(event)) return true
        return super.onTouchEvent(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        return when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN -> { moveSelection(1); true }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_VOLUME_UP -> { moveSelection(-1); true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A -> { activate(); true }
            KeyEvent.KEYCODE_BACK -> { goBack(); true }
            else -> super.dispatchKeyEvent(event)
        }
    }

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(e: MotionEvent): Boolean { activate(); return true }
        override fun onLongPress(e: MotionEvent) { goBack() }
        override fun onDoubleTap(e: MotionEvent): Boolean { goBack(); return true }
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            val dy = e2.y - (e1?.y ?: e2.y)
            if (kotlin.math.abs(dy) < 40) return false
            moveSelection(if (dy > 0) 1 else -1)
            return true
        }
    }

    private fun moveSelection(delta: Int) {
        when (view) {
            View.INBOX -> { inboxIdx = (inboxIdx + delta).coerceIn(0, chats.size); render() }
            View.MENU -> { menuIdx = (menuIdx + delta).coerceIn(0, menuItems().size - 1); render() }
            View.SEARCH_RESULTS -> { searchIdx = (searchIdx + delta).coerceIn(0, maxOf(0, searchResults.size - 1)); render() }
            View.PREVIEW -> { previewIdx = (previewIdx + delta).coerceIn(0, 1); render() }
            View.REPLY_MENU -> { replyMenuIdx = (replyMenuIdx + delta).coerceIn(0, 1); render() }
            View.QUICK -> { quickIdx = (quickIdx + delta).coerceIn(0, maxOf(0, quickMessages.size - 1)); render() }
            View.MESSAGE_ACTIONS -> { actionIdx = (actionIdx + delta).coerceIn(0, maxOf(0, actionItems.size - 1)); render() }
            View.REACT -> { reactIdx = (reactIdx + delta).coerceIn(0, EMOJIS.size - 1); render() }
            View.MESSAGES -> moveMessage(delta)
            View.MESSAGE_DETAIL -> { val m = selectedMessage(); if (m != null && !isPhoto(m)) hud.scrollDetailBy(delta) }
            else -> Unit
        }
    }

    private fun activate() {
        when (view) {
            View.INBOX -> {
                if (inboxIdx == 0) { menuIdx = 0; view = View.MENU; render() }
                else chats.getOrNull(inboxIdx - 1)?.let { openChat(it, View.INBOX) }
            }
            View.MENU -> menuItems().getOrNull(menuIdx)?.action?.invoke()
            View.SEARCH_RECORDING -> stopVoice()
            View.SEARCH_RESULTS -> searchResults.getOrNull(searchIdx)?.let { openChat(it, View.SEARCH_RESULTS) }
            View.MESSAGES -> openDetail()
            View.MESSAGE_DETAIL -> openActions()
            View.MESSAGE_ACTIONS -> actionItems.getOrNull(actionIdx)?.second?.invoke()
            View.REACT -> reactWith(EMOJIS[reactIdx].first)
            View.REPLY_MENU -> if (replyMenuIdx == 0) { view = View.VOICE_IDLE; render() } else openQuick()
            View.QUICK -> sendQuick()
            View.VOICE_IDLE -> startVoice(VoiceMode.REPLY)
            View.RECORDING -> stopVoice()
            View.PREVIEW -> confirmSend(if (previewIdx == 0) SendMode.TEXT else SendMode.AUDIO)
            View.FEEDBACK -> leaveFeedback()
            else -> Unit
        }
    }

    private fun goBack() {
        when (view) {
            View.INBOX -> finish()
            View.MENU -> { view = View.INBOX; render() }
            View.SEARCH_RECORDING -> { cancelMic(); view = View.MENU; render() }
            View.SEARCH_RESULTS -> { view = View.MENU; render() }
            View.MESSAGES -> { view = messagesReturn; render() }
            View.MESSAGE_DETAIL -> { view = View.MESSAGES; render() }
            View.MESSAGE_ACTIONS -> { view = View.MESSAGE_DETAIL; render() }
            View.REACT -> { view = View.MESSAGE_ACTIONS; render() }
            View.REPLY_MENU -> { view = View.MESSAGE_ACTIONS; render() }
            View.QUICK -> { view = View.REPLY_MENU; render() }
            View.VOICE_IDLE -> { view = View.REPLY_MENU; render() }
            View.RECORDING -> { cancelMic(); view = View.VOICE_IDLE; render() }
            View.PREVIEW -> { bridge?.send(GlassesToPhoneMessage.CancelVoice); view = View.MESSAGES; render() }
            View.FEEDBACK -> leaveFeedback()
            View.TRANSCRIBING, View.SENDING -> Unit
        }
    }

    /* ---------------- conversation ---------------- */

    private fun openChat(chat: Chat, from: View) {
        currentChat = chat
        messages = emptyList()
        msgSelected = 0
        restoreMessageId = ""
        replyToId = ""
        replyFromMe = false
        atStart = true
        convLoading = true
        messagesReturn = from
        view = View.MESSAGES
        hud.renderText(header(chat.name), "Carregando mensagens...", "Aguarde...")
        bridge?.send(GlassesToPhoneMessage.OpenChat(chat.boxId, chat.id, MSG_LIMIT))
    }

    private fun moveMessage(delta: Int) {
        if (convLoading) return
        if (delta > 0) {
            if (msgSelected < messages.lastIndex) { msgSelected++; render() }
        } else {
            if (msgSelected > 0) { msgSelected--; render() } else loadOlder()
        }
    }

    private fun loadOlder() {
        val chat = currentChat ?: return
        if (atStart || convLoading || messages.isEmpty()) return
        convLoading = true
        restoreMessageId = messages.first().id // keep the current oldest in focus
        render()
        bridge?.send(GlassesToPhoneMessage.LoadOlder(chat.boxId, chat.id, messages.size + MSG_LIMIT))
    }

    private fun selectedMessage(): Message? = messages.getOrNull(msgSelected)

    private fun openDetail() {
        val m = selectedMessage() ?: return
        detailMsgId = m.id
        detailBitmap = null
        detailImageError = null
        detailImageLoading = isPhoto(m)
        if (isPhoto(m)) {
            val chat = currentChat
            bridge?.send(GlassesToPhoneMessage.RequestImage(chat?.boxId.orEmpty(), chat?.id.orEmpty(), m.id, m.isOutgoing))
        }
        view = View.MESSAGE_DETAIL
        render()
    }

    private fun isPhoto(m: Message): Boolean = m.media == "[photo]"

    private fun messageDetailText(m: Message): String =
        "${who(m)} - ${formatTime(m.date)}\n\n${messageBody(m)}"

    private fun openActions() {
        val m = selectedMessage() ?: return
        val items = ArrayList<Pair<String, () -> Unit>>()
        if (canSend) {
            items += "Responder" to { startReply(m) }
            items += "Reagir" to { reactIdx = 0; view = View.REACT; render() }
        }
        if (m.isPlayableAudio) items += "Tocar audio" to { playAudio(m) }
        if (items.isEmpty()) return // read-only text message: nothing to do
        actionItems = items
        actionIdx = 0
        view = View.MESSAGE_ACTIONS
        render()
    }

    private fun startReply(m: Message) {
        replyToId = m.id
        replyFromMe = m.isOutgoing
        replyMenuIdx = 0
        view = View.REPLY_MENU
        render()
    }

    private fun reactWith(emoji: String) {
        val chat = currentChat ?: return
        val m = selectedMessage() ?: return
        bridge?.send(GlassesToPhoneMessage.SendReaction(chat.boxId, chat.id, m.id, emoji, m.isOutgoing))
        view = View.SENDING
        render()
    }

    private fun playAudio(m: Message) {
        val chat = currentChat ?: return
        bridge?.send(GlassesToPhoneMessage.PlayAudio(chat.boxId, chat.id, m.id, m.isOutgoing))
        view = View.SENDING
        render()
    }

    private fun startVoice(mode: VoiceMode) {
        if (!hasMicPermission()) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_PERMS)
            return
        }
        voiceMode = mode
        val chat = currentChat
        val rTo = if (mode == VoiceMode.REPLY) replyToId else ""
        bridge?.send(GlassesToPhoneMessage.StartVoice(mode, chat?.boxId.orEmpty(), chat?.id.orEmpty(), rTo, replyFromMe))
        val capture = MicCapture { chunk ->
            bridge?.send(GlassesToPhoneMessage.AudioChunk(Base64.encodeToString(chunk, Base64.NO_WRAP)))
        }
        mic = capture
        if (!capture.start()) {
            feedbackText = "Microfone indisponivel nos oculos."
            feedbackRefetch = false
            view = View.FEEDBACK
            bridge?.send(GlassesToPhoneMessage.CancelVoice)
            render()
            return
        }
        view = if (mode == VoiceMode.SEARCH) View.SEARCH_RECORDING else View.RECORDING
        render()
    }

    private fun stopVoice() {
        stopMic()
        bridge?.send(GlassesToPhoneMessage.EndVoice)
        view = View.TRANSCRIBING
        render()
    }

    private fun cancelMic() {
        stopMic()
        bridge?.send(GlassesToPhoneMessage.CancelVoice)
    }

    private fun stopMic() {
        mic?.stop()
        mic = null
    }

    private fun confirmSend(mode: SendMode) {
        bridge?.send(GlassesToPhoneMessage.ConfirmSend(mode))
        view = View.SENDING
        render()
    }

    private fun openQuick() {
        quickIdx = 0
        view = View.QUICK
        bridge?.send(GlassesToPhoneMessage.RequestQuick)
        render()
    }

    private fun sendQuick() {
        val chat = currentChat ?: return
        val qm = quickMessages.getOrNull(quickIdx) ?: return
        bridge?.send(GlassesToPhoneMessage.SendText(chat.boxId, chat.id, qm.body, replyToId, replyFromMe))
        view = View.SENDING
        render()
    }

    private fun startSearch() = startVoice(VoiceMode.SEARCH)

    private fun setFilter(filter: String) {
        activeFilter = filter
        inboxIdx = if (filter == "all") inboxIdx else 1
        inboxLoading = true
        view = View.INBOX
        bridge?.send(GlassesToPhoneMessage.RequestInbox(filter))
        render()
    }

    private fun leaveFeedback() {
        val chat = currentChat
        if (feedbackRefetch && chat != null) {
            restoreMessageId = ""
            convLoading = true
            view = View.MESSAGES
            hud.renderText(header(chat.name), "Carregando mensagens...", "Aguarde...")
            bridge?.send(GlassesToPhoneMessage.OpenChat(chat.boxId, chat.id, MSG_LIMIT))
        } else if (chat != null && messages.isNotEmpty()) {
            view = View.MESSAGES
            render()
        } else {
            view = View.INBOX
            render()
        }
    }

    /* ---------------- helpers ---------------- */

    private class MenuEntry(val iconRes: Int, val label: String, val action: () -> Unit)

    private fun menuItems(): List<MenuEntry> {
        val items = ArrayList<MenuEntry>()
        items += MenuEntry(0, "Buscar") { startSearch() }
        items += MenuEntry(0, "Inbox geral") { setFilter("all") }
        items += MenuEntry(0, "Nao lidas") { setFilter("unread") }
        chats.distinctBy { it.boxId }.forEach { c ->
            items += MenuEntry(iconFor(c.channel), boxDisplayLabel(c)) { setFilter(c.boxId) }
        }
        return items
    }

    private fun channelName(kind: ChannelKind): String = when (kind) {
        ChannelKind.WHATSAPP -> "WhatsApp"
        ChannelKind.TELEGRAM -> "Telegram"
        ChannelKind.GMAIL -> "Gmail"
        ChannelKind.GITHUB -> "GitHub"
    }

    /** Friendly box label ("WhatsApp" / "WhatsApp 2") with no bracket glyph. */
    private fun boxDisplayLabel(c: Chat): String {
        val acct = c.boxLabel.filter { it.isDigit() }
        return channelName(c.channel) + if (acct.isNotBlank()) " $acct" else ""
    }

    private fun filterLabel(): String = when (activeFilter) {
        "all" -> "Caixa"
        "unread" -> "Nao lidas"
        else -> chats.firstOrNull { it.boxId == activeFilter }?.let { boxDisplayLabel(it) } ?: "Caixa"
    }

    private fun iconFor(channel: ChannelKind): Int = when (channel) {
        ChannelKind.WHATSAPP -> R.drawable.ic_ch_whatsapp
        ChannelKind.TELEGRAM -> R.drawable.ic_ch_telegram
        ChannelKind.GMAIL -> R.drawable.ic_ch_gmail
        ChannelKind.GITHUB -> R.drawable.ic_ch_github
    }

    private fun chatRowText(c: Chat): String {
        val acct = c.boxLabel.filter { it.isDigit() } // "[W2]" -> "2" when several accounts of a type
        val prefix = if (acct.isNotBlank()) "$acct " else ""
        val unread = if (c.unreadCount > 0) " (${c.unreadCount})" else ""
        val type = when (c.type) {
            ChatType.GROUP -> " [G]"
            ChatType.CHANNEL -> " [C]"
            else -> ""
        }
        return oneLine("$prefix${c.name}$type$unread")
    }

    private fun who(m: Message): String = if (m.isOutgoing) "Eu" else m.senderName.ifBlank { "?" }

    private fun messageBody(m: Message): String = when {
        m.isPlayableAudio -> "[voz ${fmtDur(m.durationSec)}]  (toque em Tocar audio)"
        m.text.isNotBlank() -> m.text.replace("\r", "").trim()
        m.media != null -> m.media!!
        else -> "(sem conteudo)"
    }

    private fun messageFull(m: Message): String {
        val b = messageBody(m)
        val capped = if (b.length > MSG_CHAR_CAP) b.take(MSG_CHAR_CAP).trimEnd() + "\u2026" else b
        return "${who(m)} - ${formatTime(m.date)}\n$capped"
    }

    private fun fmtDur(sec: Int): String = if (sec <= 0) "0:00" else "%d:%02d".format(sec / 60, sec % 60)

    private fun header(ctx: String): String = "Rokid Inbox - ${oneLine(ctx)}".take(46)

    private fun oneLine(s: String): String = s.replace(Regex("\\s+"), " ").trim()

    private fun formatTime(iso: String?): String {
        iso ?: return ""
        return runCatching { Instant.parse(iso).atZone(ZoneId.systemDefault()).format(TIME_FMT) }.getOrDefault("")
    }

    private fun requestPermissionsIfNeeded() {
        val missing = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ContextCompat.checkSelfPermission(this@InboxGlassesActivity, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                    add(Manifest.permission.BLUETOOTH_CONNECT)
                if (ContextCompat.checkSelfPermission(this@InboxGlassesActivity, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
                    add(Manifest.permission.BLUETOOTH_SCAN)
            } else if (ContextCompat.checkSelfPermission(this@InboxGlassesActivity, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            if (ContextCompat.checkSelfPermission(this@InboxGlassesActivity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                add(Manifest.permission.RECORD_AUDIO)
        }
        if (missing.isNotEmpty()) ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQ_PERMS)
    }

    private fun hasBluetoothPermissions(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private companion object {
        private const val REQ_PERMS = 4001
        private const val MSG_LIMIT = 20
        private const val MSG_CHAR_CAP = 200
        private const val CONV_LINE_BUDGET = 13
        private const val CONV_CHARS_PER_LINE = 32
        private const val MENU_ROW = "\u00BB Menu / Buscar"
        private val SPINNER = listOf("|", "/", "-", "\\")
        private val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM HH:mm")
        private val EMOJIS = listOf(
            "\uD83D\uDC4D" to "Curtir",
            "\u2764\uFE0F" to "Amei",
            "\uD83D\uDE02" to "Haha",
            "\uD83D\uDE2E" to "Uau",
            "\uD83D\uDE22" to "Triste",
            "\uD83D\uDE4F" to "Obrigado",
        )
    }
}
