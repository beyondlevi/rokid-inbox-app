package com.rokid.inbox.phone

import android.content.Context
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.rokid.inbox.contracts.ChannelKind
import com.rokid.inbox.contracts.LocaleManager
import com.rokid.inbox.phone.channels.ChannelService
import com.rokid.inbox.phone.channels.GitHubService
import com.rokid.inbox.phone.channels.GmailService
import com.rokid.inbox.phone.channels.TelegramService
import com.rokid.inbox.phone.channels.WhatsAppService
import kotlinx.coroutines.runBlocking

/** Native settings: add/remove inboxes, set the OpenAI key, and pick the language. */
class InboxSettingsActivity : AppCompatActivity() {
    private lateinit var container: LinearLayout
    private val store by lazy { InboxConfigStore(applicationContext) }

    private val green get() = ContextCompat.getColor(this, R.color.phosphor_primary)
    private val dim get() = ContextCompat.getColor(this, R.color.phosphor_dim)
    private val bright get() = ContextCompat.getColor(this, R.color.phosphor_text_bright)

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleManager.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }
        setContentView(
            ScrollView(this).apply {
                setBackgroundColor(ContextCompat.getColor(this@InboxSettingsActivity, R.color.phosphor_bg))
                addView(container)
            },
        )
        rebuild()
    }

    private fun rebuild() {
        container.removeAllViews()
        container.addView(title(getString(R.string.settings_title)))
        container.addView(spacer(dp(16)))

        container.addView(label(getString(R.string.label_language)))
        container.addView(languageRow())

        container.addView(spacer(dp(20)))
        container.addView(label(getString(R.string.label_connected_inboxes)))
        val boxes = store.getBoxes()
        if (boxes.isEmpty()) container.addView(body(getString(R.string.settings_none_yet)))
        boxes.forEach { box ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(ImageView(this).apply {
                setImageResource(iconFor(box.kind))
                setColorFilter(green, PorterDuff.Mode.SRC_IN)
                val s = dp(18)
                layoutParams = LinearLayout.LayoutParams(s, s).apply { marginEnd = dp(8) }
            })
            row.addView(body(box.name.ifBlank { box.kind.name }).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(button(getString(R.string.btn_remove)) {
                store.deleteBox(box.id)
                InboxGraph.reloadFromConfig()
                rebuild()
            })
            container.addView(row)
        }

        container.addView(spacer(dp(16)))
        container.addView(button(getString(R.string.btn_add_whatsapp)) { addWhatsApp() })
        container.addView(spacer(dp(8)))
        container.addView(button(getString(R.string.btn_add_github)) { addGitHub() })
        container.addView(spacer(dp(8)))
        container.addView(button(getString(R.string.btn_add_telegram)) { addTelegram() })
        container.addView(spacer(dp(8)))
        container.addView(button(getString(R.string.btn_add_gmail)) { addGmail() })

        container.addView(spacer(dp(24)))
        container.addView(label(getString(R.string.label_openai)))
        container.addView(body(getString(R.string.openai_hint)))
        val keyField = editText("sk-…", store.getOpenAiKey(), password = true)
        container.addView(keyField)
        container.addView(button(getString(R.string.btn_save_openai)) {
            store.setOpenAiKey(keyField.text.toString().trim())
            InboxGraph.reloadFromConfig()
            toast(getString(R.string.toast_saved))
        })

        container.addView(spacer(dp(24)))
        container.addView(label(getString(R.string.label_quick)))
        store.getQuickMessages().forEach { qm ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(body("• ${qm.title}").apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(button(getString(R.string.btn_remove)) {
                store.setQuickMessages(store.getQuickMessages().filterNot { it.title == qm.title && it.body == qm.body })
                rebuild()
            })
            container.addView(row)
        }
        container.addView(button(getString(R.string.btn_add_quick)) { addQuickMessage() })
    }

    /* ---------------- language ---------------- */

    private fun languageRow(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val current = LocaleManager.current(this)
        row.addView(langButton(getString(R.string.lang_english), LocaleManager.EN, current))
        row.addView(spacer(dp(10)).apply {
            layoutParams = LinearLayout.LayoutParams(dp(10), dp(1))
        })
        row.addView(langButton(getString(R.string.lang_portuguese), LocaleManager.PT, current))
        return row
    }

    private fun langButton(text: String, lang: String, current: String) = Button(this).apply {
        this.text = if (lang == current) "◉ $text" else "○ $text"
        setTextColor(if (lang == current) green else bright)
        setBackgroundColor(Color.parseColor(if (lang == current) "#0F2A0F" else "#0A1A0A"))
        typeface = Typeface.MONOSPACE
        setOnClickListener { applyLanguage(lang) }
    }

    private fun applyLanguage(lang: String) {
        if (!LocaleManager.save(this, lang)) return
        InboxGraph.broadcastLocale(lang)
        recreate()
    }

    /* ---------------- add channels ---------------- */

    private fun addQuickMessage() {
        val title = editText(getString(R.string.quick_hint_title), "")
        val bodyField = editText(getString(R.string.quick_hint_body), "")
        formDialog(
            getString(R.string.quick_dialog_title),
            listOf(getString(R.string.quick_field_title) to title, getString(R.string.quick_field_text) to bodyField),
        ) {
            val t = title.text.toString().trim()
            val b = bodyField.text.toString().trim()
            if (t.isBlank() || b.isBlank()) { toast(getString(R.string.toast_fill_title_text)); return@formDialog }
            store.setQuickMessages(store.getQuickMessages() + com.rokid.inbox.contracts.QuickMessage(t, b))
            rebuild()
        }
    }

    private fun addWhatsApp() {
        val serverUrl = editText(getString(R.string.wa_hint_server), "")
        val instance = editText(getString(R.string.wa_hint_instance), "")
        val apiKey = editText(getString(R.string.field_api_key), "", password = true)
        formDialog(
            getString(R.string.wa_dialog_title),
            listOf(
                getString(R.string.wa_field_server) to serverUrl,
                getString(R.string.wa_field_instance) to instance,
                getString(R.string.field_api_key) to apiKey,
            ),
        ) {
            val cfg = mapOf(
                "serverUrl" to normalizeUrl(serverUrl.text.toString()),
                "instance" to instance.text.toString().trim(),
                "apiKey" to apiKey.text.toString().trim(),
            )
            validateAndSave(ChannelKind.WHATSAPP, cfg["instance"]!!.ifBlank { "WhatsApp" }, cfg) {
                WhatsAppService("probe", cfg["serverUrl"]!!, cfg["instance"]!!, cfg["apiKey"]!!)
            }
        }
    }

    private fun addGitHub() {
        val token = editText(getString(R.string.gh_hint_token), "", password = true)
        val query = editText("is:open is:pr involves:@me", "")
        formDialog(
            getString(R.string.gh_dialog_title),
            listOf(getString(R.string.gh_field_token) to token, getString(R.string.gh_field_query) to query),
        ) {
            val cfg = mapOf(
                "token" to token.text.toString().trim(),
                "query" to query.text.toString().trim(),
            )
            validateAndSave(ChannelKind.GITHUB, "GitHub PRs", cfg) {
                GitHubService("probe", cfg["token"]!!, cfg["query"]!!)
            }
        }
    }

    private fun addGmail() {
        val clientId = editText(getString(R.string.gm_field_client_id), "")
        val clientSecret = editText(getString(R.string.gm_field_client_secret), "", password = true)
        val refreshToken = editText(getString(R.string.gm_field_refresh_token), "", password = true)
        formDialog(
            getString(R.string.gm_dialog_title),
            listOf(
                getString(R.string.gm_field_client_id) to clientId,
                getString(R.string.gm_field_client_secret) to clientSecret,
                getString(R.string.gm_field_refresh_token) to refreshToken,
            ),
        ) {
            val cfg = mapOf(
                "clientId" to clientId.text.toString().trim(),
                "clientSecret" to clientSecret.text.toString().trim(),
                "refreshToken" to refreshToken.text.toString().trim(),
            )
            validateAndSave(ChannelKind.GMAIL, "Gmail", cfg) {
                GmailService("probe", cfg["clientId"]!!, cfg["clientSecret"]!!, cfg["refreshToken"]!!)
            }
        }
    }

    private fun addTelegram() {
        val serverUrl = editText(getString(R.string.tg_hint_url), "")
        val apiKey = editText(getString(R.string.field_api_key), "", password = true)
        formDialog(
            getString(R.string.tg_dialog_title),
            listOf(getString(R.string.tg_field_url) to serverUrl, getString(R.string.field_api_key) to apiKey),
        ) {
            val cfg = mapOf(
                "serverUrl" to normalizeUrl(serverUrl.text.toString()),
                "apiKey" to apiKey.text.toString().trim(),
            )
            validateAndSave(ChannelKind.TELEGRAM, "Telegram", cfg) {
                TelegramService("probe", cfg["serverUrl"]!!, cfg["apiKey"]!!)
            }
        }
    }

    private fun validateAndSave(
        kind: ChannelKind,
        name: String,
        config: Map<String, String>,
        buildProbe: () -> ChannelService,
    ) {
        toast(getString(R.string.toast_testing))
        Thread {
            val result = runCatching { runBlocking { buildProbe().ping() } }
            runOnUiThread {
                if (result.isSuccess) {
                    store.addBox(BoxConfig(InboxConfigStore.newBoxId(kind), kind, name, config))
                    InboxGraph.reloadFromConfig()
                    toast(getString(R.string.toast_connected))
                    rebuild()
                } else {
                    toast(getString(R.string.toast_failed, result.exceptionOrNull()?.message?.take(160)))
                }
            }
        }.start()
    }

    private fun formDialog(titleText: String, fields: List<Pair<String, EditText>>, onSave: () -> Unit) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        fields.forEach { (labelText, field) ->
            layout.addView(label(labelText))
            layout.addView(field)
            layout.addView(spacer(dp(8)))
        }
        AlertDialog.Builder(this, R.style.Theme_PhosphorDialog)
            .setTitle(titleText)
            .setView(ScrollView(this).apply { addView(layout) })
            .setPositiveButton(getString(R.string.dialog_test_connect)) { _, _ -> onSave() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /* ---------------- view helpers ---------------- */

    private fun iconFor(kind: ChannelKind): Int = when (kind) {
        ChannelKind.WHATSAPP -> R.drawable.ic_ch_whatsapp
        ChannelKind.TELEGRAM -> R.drawable.ic_ch_telegram
        ChannelKind.GMAIL -> R.drawable.ic_ch_gmail
        ChannelKind.GITHUB -> R.drawable.ic_ch_github
    }

    private fun title(text: String) = TextView(this).apply {
        this.text = text; setTextColor(green); textSize = 20f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text; setTextColor(dim); textSize = 11f
        typeface = Typeface.MONOSPACE; letterSpacing = 0.1f
    }

    private fun body(text: String) = TextView(this).apply {
        this.text = text; setTextColor(bright); textSize = 14f; typeface = Typeface.MONOSPACE
    }

    private fun editText(hintText: String, value: String, password: Boolean = false) = EditText(this).apply {
        hint = hintText
        setText(value)
        setTextColor(bright)
        setHintTextColor(ContextCompat.getColor(this@InboxSettingsActivity, R.color.phosphor_text_ghost))
        typeface = Typeface.MONOSPACE
        textSize = 14f
        inputType = if (password) {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        } else {
            InputType.TYPE_CLASS_TEXT
        }
    }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text; setTextColor(green); setBackgroundColor(Color.parseColor("#0A1A0A"))
        typeface = Typeface.MONOSPACE; setOnClickListener { onClick() }
    }

    private fun spacer(h: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, h)
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun normalizeUrl(url: String): String {
        val t = url.trim().trimEnd('/')
        return if (t.matches(Regex("^https?://.*", RegexOption.IGNORE_CASE))) t else "https://$t"
    }
}
