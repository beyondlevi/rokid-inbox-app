package com.rokid.inbox.glasses

import android.Manifest
import android.content.Context
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
import com.rokid.inbox.contracts.LocaleManager
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
 * per-message actions: reply (quoting), emoji react, play audio, AI describe.
 *
 * The UI language (en/pt) is chosen on the phone and pushed here via SetLocale.
 */
class InboxGlassesActivity : AppCompatActivity() {

    private enum class View {
        INBOX, MENU, SEARCH_RECORDING, SEARCH_RESULTS, MESSAGES, MESSAGE_DETAIL, MESSAGE_ACTIONS, REACT,
        REPLY_MENU, QUICK, VOICE_IDLE, RECORDING, TRANSCRIBING, PREVIEW, SENDING, FEEDBACK,
        DESCRIBING, DESCRIPTION,
    }

    private lateinit var hud: InboxHudView
    private var bridge: HybridBridge? = null
    private var unsubscribe: (() -> Unit)? = null
    private var mic: MicCapture? = null
    private lateinit var gestures: GestureDetector

    private var view = View.INBOX
    private var statusLabel = ""
    private var connected = false
    private var createdLang = ""

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

    // Reply target (set when "Reply" is chosen on a specific message).
    private var replyToId = ""
    private var replyFromMe = false

    private var actionItems: List<Pair<String, () -> Unit>> = emptyList()

    // Message detail (expanded reader / photo view).
    private var detailMsgId = ""
    private var detailBitmap: Bitmap? = null
    private var detailImageLoading = false
    private var detailImageError: String? = null

    // AI description (image/file -> OpenAI text).
    private var descMsgId = ""
    private var descText = ""
    private var descError: String? = null

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleManager.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createdLang = LocaleManager.current(this)
        statusLabel = getString(R.string.connecting_default)
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
            is PhoneToGlassesMessage.SetLocale -> {
                LocaleManager.save(this, message.language)
                if (LocaleManager.current(this) != createdLang) recreate()
            }
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
                    if (message.mode == SendMode.TEXT) getString(R.string.sent_text) else getString(R.string.sent_audio)
                } else {
                    getString(R.string.send_error_fmt, message.error.orEmpty().take(120))
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
                        detailImageError = if (detailBitmap == null) getString(R.string.img_unavailable) else null
                    } else {
                        detailBitmap = null
                        detailImageError = message.error ?: getString(R.string.img_unavailable)
                    }
                    if (view == View.MESSAGE_DETAIL) render()
                }
            }
            is PhoneToGlassesMessage.DescriptionResult -> {
                if (message.messageId == descMsgId && descMsgId.isNotBlank()) {
                    if (message.ok) {
                        descText = message.text
                        descError = null
                    } else {
                        descText = ""
                        descError = message.error ?: getString(R.string.desc_empty)
                    }
                    view = View.DESCRIPTION
                    render()
                }
            }
        }
    }

    /* ---------------- rendering ---------------- */

    private fun render() {
        when (view) {
            View.INBOX -> renderInbox()
            View.MENU -> renderMenu()
            View.SEARCH_RECORDING -> hud.renderText(header(getString(R.string.hdr_search)), getString(R.string.search_recording_body), getString(R.string.hint_search))
            View.SEARCH_RESULTS -> renderSearchResults()
            View.MESSAGES -> renderMessages()
            View.MESSAGE_DETAIL -> renderMessageDetail()
            View.MESSAGE_ACTIONS -> hud.renderList(header(getString(R.string.hdr_actions)), actionItems.map { it.first }, actionIdx, getString(R.string.hint_nav_open_back))
            View.REACT -> hud.renderList(header(getString(R.string.hdr_react)), EMOJIS.map { getString(R.string.react_row_fmt, it.first, getString(it.second)) }, reactIdx, getString(R.string.hint_react))
            View.REPLY_MENU -> hud.renderList(header(getString(R.string.hdr_reply)), listOf(getString(R.string.reply_voice), getString(R.string.reply_quick)), replyMenuIdx, getString(R.string.hint_nav_open_back))
            View.QUICK -> renderQuick()
            View.VOICE_IDLE -> hud.renderText(header(getString(R.string.hdr_voice)), getString(R.string.voice_idle_body), getString(R.string.hint_record_back))
            View.RECORDING -> hud.renderText(header(getString(R.string.hdr_voice)), getString(R.string.recording_body), getString(R.string.hint_stop_cancel))
            View.TRANSCRIBING -> renderLoadingScreen(getString(R.string.load_transcribing))
            View.PREVIEW -> renderPreview()
            View.SENDING -> renderLoadingScreen(getString(R.string.load_sending))
            View.FEEDBACK -> hud.renderText(header(getString(R.string.hdr_done)), feedbackText, getString(R.string.hint_tap_or_back))
            View.DESCRIBING -> renderLoadingScreen(getString(R.string.load_describing))
            View.DESCRIPTION -> renderDescription()
        }
        scheduleSpinner()
    }

    /* ---------------- spinner ---------------- */

    private fun isLoading(): Boolean = when (view) {
        View.TRANSCRIBING, View.SENDING, View.DESCRIBING -> true
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
        hud.renderText(header(label), getString(R.string.loading_fmt, spinner(), label), getString(R.string.wait), big = true)
    }

    private fun renderInbox() {
        if (!connected && chats.isEmpty()) {
            hud.renderText(header(getString(R.string.hdr_connecting)), statusLabel, getString(R.string.hint_exit))
            return
        }
        if (connected && (!inboxLoaded || inboxLoading)) {
            renderLoadingScreen(getString(R.string.load_loading))
            return
        }
        val rows = ArrayList<InboxHudView.Row>()
        rows += InboxHudView.Row(0, getString(R.string.menu_row))
        chats.forEach { rows += InboxHudView.Row(iconFor(it.channel), chatRowText(it), chatRowSubtitle(it)) }
        inboxIdx = inboxIdx.coerceIn(0, rows.size - 1)
        val ctx = "${filterLabel()} · ${chats.size}"
        hud.renderChatList(header(ctx), rows, inboxIdx, getString(R.string.hint_nav_open_exit))
    }

    private fun renderMenu() {
        val items = menuItems()
        menuIdx = menuIdx.coerceIn(0, items.size - 1)
        hud.renderChatList(
            header(getString(R.string.hdr_menu)),
            items.map { InboxHudView.Row(it.iconRes, it.label) },
            menuIdx,
            getString(R.string.hint_nav_open_back),
        )
    }

    private fun renderSearchResults() {
        if (searchResults.isEmpty()) {
            hud.renderText(header(getString(R.string.hdr_search)), getString(R.string.search_none), getString(R.string.hint_menu_exit))
            return
        }
        searchIdx = searchIdx.coerceIn(0, searchResults.size - 1)
        val rows = searchResults.map { InboxHudView.Row(iconFor(it.channel), chatRowText(it), chatRowSubtitle(it)) }
        hud.renderChatList(header(getString(R.string.search_ctx_fmt, searchResults.size)), rows, searchIdx, getString(R.string.hint_nav_open_menu))
    }

    private fun renderMessages() {
        val chat = currentChat ?: return
        if (messages.isEmpty()) {
            if (convLoading) renderLoadingScreen(getString(R.string.load_messages))
            else hud.renderText(header(chat.name), getString(R.string.conv_empty), if (canSend) getString(R.string.hint_reply_back) else getString(R.string.hint_back))
            return
        }
        val texts = messages.map { messageFull(it) }
        val ctx = oneLine(chat.name) + if (convLoading) getString(R.string.conv_loading_suffix) else getString(R.string.conv_ctx_fmt, msgSelected + 1, messages.size)
        hud.renderConversation(
            header(ctx),
            texts,
            msgSelected.coerceIn(0, messages.lastIndex),
            olderAbove = !atStart,
            hintText = getString(R.string.hint_nav_open_back),
        )
    }

    private fun renderMessageDetail() {
        val m = selectedMessage() ?: run { view = View.MESSAGES; render(); return }
        if (isPhoto(m)) {
            when {
                detailImageLoading -> renderLoadingScreen(getString(R.string.load_image))
                detailBitmap != null -> hud.renderImage(header(getString(R.string.hdr_photo)), detailBitmap!!, m.text.trim(), getString(R.string.hint_photo_actions))
                else -> hud.renderText(header(getString(R.string.hdr_photo)), detailImageError ?: getString(R.string.img_unavailable), getString(R.string.hint_photo_actions))
            }
        } else {
            hud.renderDetail(
                header(oneLine(currentChat?.name.orEmpty())),
                messageDetailText(m),
                getString(R.string.hint_scroll_actions),
            )
        }
    }

    /** Fill the screen with as many full messages as fit around the selection. */
    private fun renderDescription() {
        val body = descError?.let { getString(R.string.desc_error_fmt, it) } ?: descText.ifBlank { getString(R.string.desc_empty) }
        hud.renderDetail(header(getString(R.string.hdr_description)), body, getString(R.string.hint_scroll_back))
    }

    private fun renderQuick() {
        if (quickMessages.isEmpty()) {
            hud.renderText(header(getString(R.string.hdr_quick)), getString(R.string.quick_loading), getString(R.string.hint_back))
            return
        }
        quickIdx = quickIdx.coerceIn(0, quickMessages.size - 1)
        hud.renderList(header(getString(R.string.hdr_quick)), quickMessages.map { it.title }, quickIdx, getString(R.string.hint_quick))
    }

    private fun renderPreview() {
        val safe = oneLine(pendingTranscription).ifBlank { getString(R.string.preview_empty) }
        val quoted = if (replyToId.isNotBlank()) getString(R.string.preview_quoted_suffix) else ""
        hud.renderOptions(
            header(getString(R.string.hdr_voice)),
            getString(R.string.preview_body_fmt, pendingDuration, safe, quoted),
            listOf(getString(R.string.preview_send_text), getString(R.string.preview_send_audio)),
            previewIdx,
            getString(R.string.hint_preview),
        )
    }

    /* ---------------- input ---------------- */

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (gestures.onTouchEvent(event)) return true
        return super.onTouchEvent(event)
    }

    // Timestamp of the last accepted nav move, for the gesture debounce below.
    private var lastNavAt = 0L

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        return when (event.keyCode) {
            // Full R08 ring key set (RokidPipe reference). One physical ring
            // gesture can emit two of these, so nav is debounced.
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_VOLUME_DOWN, RING_NEXT -> { nav(1); true }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_VOLUME_UP, RING_PREV -> { nav(-1); true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_SPACE,
            KeyEvent.KEYCODE_BUTTON_A, RING_SELECT -> { activate(); true }
            KeyEvent.KEYCODE_BACK -> { goBack(); true }
            else -> super.dispatchKeyEvent(event)
        }
    }

    /** The ring may present its axis as generic scroll (rotary) motion instead
     *  of key events; capture it here, move the selection, and consume it so the
     *  ScrollView never scrolls a page on its own. */
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_SCROLL) {
            val v = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            val h = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
            val axis = if (v != 0f) v else h
            if (axis != 0f) { nav(if (axis < 0f) 1 else -1); return true }
        }
        return super.onGenericMotionEvent(event)
    }

    /** Move the selection for a ring gesture. The R08 ring reports its axis
     *  inverted relative to list order (swipe down should move the selection
     *  down, i.e. to a higher index), so normalize the sign here; this is the
     *  single choke point for the key, generic-scroll and synthesized-fling ring
     *  paths. A single gesture can emit two events, so collapse repeats within
     *  [NAV_DEBOUNCE_MS] into one move. */
    private fun nav(delta: Int) {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastNavAt < NAV_DEBOUNCE_MS) return
        lastNavAt = now
        moveSelection(-delta)
    }

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        // Use onSingleTapConfirmed (not onSingleTapUp): it waits out the
        // double-tap timeout so a double-tap-back never fires a stray activate()
        // first. Long-press is intentionally unused — Hi Rokid captures it and
        // it is not part of the R08 tap/tap-tap/fling input model.
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean { activate(); return true }
        override fun onDoubleTap(e: MotionEvent): Boolean { goBack(); return true }
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            val dy = e2.y - (e1?.y ?: e2.y)
            if (kotlin.math.abs(dy) < 40) return false
            // The R08 ring presents its swipe as a synthesized temple fling whose
            // dy is inverted vs the physical gesture, so it goes through the same
            // nav() normalization as the key/scroll paths.
            nav(if (dy > 0) 1 else -1)
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
            View.DESCRIPTION -> hud.scrollDetailBy(delta)
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
            View.DESCRIBING -> { descMsgId = ""; view = View.MESSAGE_ACTIONS; render() }
            View.DESCRIPTION -> { view = View.MESSAGE_ACTIONS; render() }
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
        hud.renderText(header(chat.name), getString(R.string.loading_messages_body), getString(R.string.wait))
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
        "${who(m)} · ${formatTime(m.date)}\n\n${messageBody(m)}"

    private fun openActions() {
        val m = selectedMessage() ?: return
        val items = ArrayList<Pair<String, () -> Unit>>()
        if (canSend) {
            items += getString(R.string.act_reply) to { startReply(m) }
            items += getString(R.string.act_react) to { reactIdx = 0; view = View.REACT; render() }
        }
        if (m.isPlayableAudio) items += getString(R.string.act_play_audio) to { playAudio(m) }
        if (m.canDescribe) items += getString(R.string.act_describe) to { requestDescription(m) }
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

    private fun requestDescription(m: Message) {
        val chat = currentChat ?: return
        descMsgId = m.id
        descText = ""
        descError = null
        bridge?.send(
            GlassesToPhoneMessage.RequestDescription(
                boxId = chat.boxId,
                chatId = chat.id,
                messageId = m.id,
                fromMe = m.isOutgoing,
                isImage = m.isImageMedia,
                fileName = m.fileName,
            ),
        )
        view = View.DESCRIBING
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
            feedbackText = getString(R.string.mic_unavailable)
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
            hud.renderText(header(chat.name), getString(R.string.loading_messages_body), getString(R.string.wait))
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
        items += MenuEntry(0, getString(R.string.menu_search)) { startSearch() }
        items += MenuEntry(0, getString(R.string.menu_inbox_all)) { setFilter("all") }
        items += MenuEntry(0, getString(R.string.menu_unread)) { setFilter("unread") }
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
        "all" -> getString(R.string.filter_all)
        "unread" -> getString(R.string.filter_unread)
        else -> chats.firstOrNull { it.boxId == activeFilter }?.let { boxDisplayLabel(it) } ?: getString(R.string.filter_all)
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

    /** Second row line: "HH:mm | first characters of the last message". */
    private fun chatRowSubtitle(c: Chat): String {
        val time = formatClock(c.lastMessageDate)
        val preview = oneLine(c.lastMessagePreview)
        return when {
            time.isBlank() && preview.isBlank() -> ""
            time.isBlank() -> preview
            preview.isBlank() -> time
            else -> "$time | $preview"
        }
    }

    private fun formatClock(iso: String?): String {
        iso ?: return ""
        return runCatching { Instant.parse(iso).atZone(ZoneId.systemDefault()).format(CLOCK_FMT) }.getOrDefault("")
    }

    private fun who(m: Message): String = if (m.isOutgoing) getString(R.string.who_me) else m.senderName.ifBlank { getString(R.string.who_unknown) }

    private fun messageBody(m: Message): String = when {
        m.isPlayableAudio -> getString(R.string.msg_voice_fmt, fmtDur(m.durationSec))
        m.text.isNotBlank() -> m.text.replace("\r", "").trim()
        m.isDescribableFile && m.fileName.isNotBlank() -> getString(R.string.msg_file_fmt, m.fileName)
        m.media != null -> m.media!!
        else -> getString(R.string.msg_no_content)
    }

    private fun messageFull(m: Message): String {
        val b = messageBody(m)
        val capped = if (b.length > MSG_CHAR_CAP) b.take(MSG_CHAR_CAP).trimEnd() + "\u2026" else b
        return "${who(m)} · ${formatTime(m.date)}\n$capped"
    }

    private fun fmtDur(sec: Int): String = if (sec <= 0) "0:00" else "%d:%02d".format(sec / 60, sec % 60)

    private fun header(ctx: String): String = "Rokid Inbox · ${oneLine(ctx)}".take(46)

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
        // Raw keycodes the R08 ring may emit for navigation / select.
        private const val RING_NEXT = 183
        private const val RING_PREV = 184
        private const val RING_SELECT = 202
        // Collapse the twin event one ring gesture can emit into a single move.
        private const val NAV_DEBOUNCE_MS = 220L
        private val SPINNER = listOf("|", "/", "-", "\\")
        private val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM HH:mm")
        private val CLOCK_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        private val EMOJIS = listOf(
            "\uD83D\uDC4D" to R.string.emoji_like,
            "\u2764\uFE0F" to R.string.emoji_love,
            "\uD83D\uDE02" to R.string.emoji_haha,
            "\uD83D\uDE2E" to R.string.emoji_wow,
            "\uD83D\uDE22" to R.string.emoji_sad,
            "\uD83D\uDE4F" to R.string.emoji_thanks,
        )
    }
}
