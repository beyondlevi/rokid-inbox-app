package com.rokid.inbox.phone

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.startForegroundService
import com.rokid.inbox.contracts.ChannelKind
import com.rokid.inbox.contracts.LocaleManager

/** Host status screen: shows the glasses link + connected boxes, opens settings. */
class InboxPhoneActivity : AppCompatActivity() {
    private lateinit var statusLine: TextView
    private lateinit var boxesContainer: LinearLayout
    private lateinit var sttLine: TextView
    private var unsubscribe: (() -> Unit)? = null
    private var createdLang: String = ""

    private val green get() = ContextCompat.getColor(this, R.color.phosphor_primary)
    private val dim get() = ContextCompat.getColor(this, R.color.phosphor_dim)
    private val bright get() = ContextCompat.getColor(this, R.color.phosphor_text_bright)

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleManager.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createdLang = LocaleManager.current(this)
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
        if (LocaleManager.current(this) != createdLang) { recreate(); return }
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
            append(if (connected) getString(R.string.home_glasses_connected) else getString(R.string.home_glasses_state, state.deviceStatus.connectionState.name))
            append("\n")
            append(state.deviceStatus.statusLabel)
        }
        statusLine.setTextColor(if (connected) green else dim)
        renderBoxes(state.boxes)
        sttLine.text = if (state.openAiConfigured) getString(R.string.openai_configured) else getString(R.string.openai_missing)
        sttLine.setTextColor(if (state.openAiConfigured) green else dim)
    }

    private fun renderBoxes(boxes: List<BoxSummary>) {
        boxesContainer.removeAllViews()
        if (boxes.isEmpty()) {
            boxesContainer.addView(body(getString(R.string.home_no_inboxes)))
            return
        }
        boxes.forEach { box ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(3), 0, dp(3))
            }
            row.addView(ImageView(this).apply {
                setImageResource(iconFor(box.kind))
                setColorFilter(green, PorterDuff.Mode.SRC_IN)
                val s = dp(18)
                layoutParams = LinearLayout.LayoutParams(s, s).apply { marginEnd = dp(10) }
            })
            row.addView(body(box.name.ifBlank { box.kind.name }))
            boxesContainer.addView(row)
        }
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(20))
        }
        root.addView(title(getString(R.string.home_title)))
        root.addView(spacer(dp(16)))
        statusLine = body(getString(R.string.home_starting)).also { root.addView(it) }
        root.addView(spacer(dp(16)))
        root.addView(label(getString(R.string.label_connected_inboxes)))
        boxesContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(boxesContainer)
        root.addView(spacer(dp(16)))
        sttLine = body("").also { root.addView(it) }
        root.addView(spacer(dp(24)))
        root.addView(button(getString(R.string.btn_inboxes_settings)) {
            startActivity(Intent(this, InboxSettingsActivity::class.java))
        })
        root.addView(spacer(dp(10)))
        root.addView(button(getString(R.string.btn_bt_settings)) {
            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        })
        root.addView(spacer(dp(16)))
        root.addView(body(getString(R.string.home_pair_hint)).also {
            it.setTextColor(dim)
        })
        return ScrollView(this).apply {
            setBackgroundColor(ContextCompat.getColor(this@InboxPhoneActivity, R.color.phosphor_bg))
            addView(root)
        }
    }

    private fun iconFor(kind: ChannelKind): Int = when (kind) {
        ChannelKind.WHATSAPP -> R.drawable.ic_ch_whatsapp
        ChannelKind.TELEGRAM -> R.drawable.ic_ch_telegram
        ChannelKind.GMAIL -> R.drawable.ic_ch_gmail
        ChannelKind.GITHUB -> R.drawable.ic_ch_github
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
