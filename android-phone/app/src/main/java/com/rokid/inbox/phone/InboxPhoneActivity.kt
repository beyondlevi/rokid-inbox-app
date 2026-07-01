package com.rokid.inbox.phone

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.startForegroundService
import com.rokid.inbox.contracts.ConnectionState

/** Host status screen: shows the glasses link + connected boxes, opens settings. */
class InboxPhoneActivity : AppCompatActivity() {
    private lateinit var statusLine: TextView
    private lateinit var boxesLine: TextView
    private lateinit var sttLine: TextView
    private var unsubscribe: (() -> Unit)? = null

    private val green get() = ContextCompat.getColor(this, R.color.phosphor_primary)
    private val dim get() = ContextCompat.getColor(this, R.color.phosphor_dim)
    private val bright get() = ContextCompat.getColor(this, R.color.phosphor_text_bright)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        InboxGraph.initialize(applicationContext)
        startForegroundService(this, Intent(this, InboxPhoneService::class.java))
        requestRuntimePermissionsIfNeeded()
    }

    override fun onStart() {
        super.onStart()
        unsubscribe = InboxGraph.stateStore.subscribe(::render)
    }

    override fun onResume() {
        super.onResume()
        if (hasRuntimePermissions()) InboxGraph.start(applicationContext)
    }

    override fun onStop() {
        unsubscribe?.invoke()
        unsubscribe = null
        super.onStop()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMS && hasRuntimePermissions()) InboxGraph.start(applicationContext)
    }

    private fun render(state: InboxPhoneViewState) {
        val connected = state.deviceStatus.bluetoothClientCount > 0
        statusLine.text = buildString {
            append(if (connected) "● GLASSES CONNECTED" else "○ ${state.deviceStatus.connectionState.name}")
            append("\n")
            append(state.deviceStatus.statusLabel)
        }
        statusLine.setTextColor(if (connected) green else dim)
        boxesLine.text = if (state.boxes.isEmpty()) {
            "No inboxes yet. Tap [ INBOXES / SETTINGS ] to add WhatsApp or GitHub."
        } else {
            state.boxes.joinToString("\n") { "${it.label} ${it.name.ifBlank { it.kind.name }}" }
        }
        sttLine.text = if (state.openAiConfigured) "STT: Whisper key configured" else "STT: no OpenAI key (voice sends audio only)"
        sttLine.setTextColor(if (state.openAiConfigured) green else dim)
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(20))
        }
        root.addView(title("[ ROKID INBOX ]"))
        root.addView(spacer(dp(16)))
        statusLine = body("Starting...").also { root.addView(it) }
        root.addView(spacer(dp(16)))
        root.addView(label("CONNECTED INBOXES"))
        boxesLine = body("...").also { root.addView(it) }
        root.addView(spacer(dp(16)))
        sttLine = body("STT: ...").also { root.addView(it) }
        root.addView(spacer(dp(24)))
        root.addView(button("[ INBOXES / SETTINGS ]") {
            startActivity(Intent(this, InboxSettingsActivity::class.java))
        })
        root.addView(spacer(dp(10)))
        root.addView(button("[ BT SETTINGS ]") {
            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        })
        root.addView(spacer(dp(16)))
        root.addView(body("Pair the glasses over Bluetooth, then open the Inbox app on the glasses.").also {
            it.setTextColor(dim)
        })
        return ScrollView(this).apply {
            setBackgroundColor(ContextCompat.getColor(this@InboxPhoneActivity, R.color.phosphor_bg))
            addView(root)
        }
    }

    private fun title(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(green)
        textSize = 22f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(dim)
        textSize = 11f
        typeface = Typeface.MONOSPACE
        letterSpacing = 0.1f
    }

    private fun body(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(bright)
        textSize = 14f
        typeface = Typeface.MONOSPACE
    }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        setTextColor(green)
        setBackgroundColor(Color.parseColor("#0A1A0A"))
        typeface = Typeface.MONOSPACE
        gravity = Gravity.CENTER
        setOnClickListener { onClick() }
    }

    private fun spacer(h: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, h)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun requestRuntimePermissionsIfNeeded() {
        val missing = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ContextCompat.checkSelfPermission(this@InboxPhoneActivity, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                    add(Manifest.permission.BLUETOOTH_CONNECT)
                if (ContextCompat.checkSelfPermission(this@InboxPhoneActivity, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
                    add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this@InboxPhoneActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (missing.isNotEmpty()) ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQ_PERMS)
    }

    private fun hasRuntimePermissions(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
    }

    private companion object {
        private const val REQ_PERMS = 3001
    }
}
