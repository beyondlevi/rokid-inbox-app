package com.rokid.inbox.phone

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.rokid.inbox.contracts.ChannelKind
import com.rokid.inbox.phone.channels.ChannelService
import com.rokid.inbox.phone.channels.GitHubService
import com.rokid.inbox.phone.channels.GmailService
import com.rokid.inbox.phone.channels.TelegramService
import com.rokid.inbox.phone.channels.WhatsAppService
import kotlinx.coroutines.runBlocking

/** Native settings: add/remove WhatsApp & GitHub inboxes and set the OpenAI key. */
class InboxSettingsActivity : AppCompatActivity() {
    private lateinit var container: LinearLayout
    private val store by lazy { InboxConfigStore(applicationContext) }

    private val green get() = ContextCompat.getColor(this, R.color.phosphor_primary)
    private val dim get() = ContextCompat.getColor(this, R.color.phosphor_dim)
    private val bright get() = ContextCompat.getColor(this, R.color.phosphor_text_bright)

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
        container.addView(title("[ INBOXES / SETTINGS ]"))
        container.addView(spacer(dp(16)))

        container.addView(label("CONNECTED INBOXES"))
        val boxes = store.getBoxes()
        if (boxes.isEmpty()) container.addView(body("None yet."))
        boxes.forEach { box ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(body("• ${box.name.ifBlank { box.kind.name }} (${box.kind.name})").apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(button("Remove") {
                store.deleteBox(box.id)
                InboxGraph.reloadFromConfig()
                rebuild()
            })
            container.addView(row)
        }

        container.addView(spacer(dp(16)))
        container.addView(button("+ Add WhatsApp (Evolution API)") { addWhatsApp() })
        container.addView(spacer(dp(8)))
        container.addView(button("+ Add GitHub PRs") { addGitHub() })
        container.addView(spacer(dp(8)))
        container.addView(button("+ Add Telegram (TDLib)") { addTelegram() })
        container.addView(spacer(dp(8)))
        container.addView(button("+ Add Gmail (read-only)") { addGmail() })

        container.addView(spacer(dp(24)))
        container.addView(label("TRANSCRIPTION (OpenAI Whisper)"))
        val keyField = editText("sk-...", store.getOpenAiKey(), password = true)
        container.addView(keyField)
        container.addView(button("Save OpenAI key") {
            store.setOpenAiKey(keyField.text.toString().trim())
            InboxGraph.reloadFromConfig()
            toast("Saved")
        })

        container.addView(spacer(dp(24)))
        container.addView(label("QUICK MESSAGES (canned replies)"))
        store.getQuickMessages().forEach { qm ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(body("• ${qm.title}").apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(button("Remove") {
                store.setQuickMessages(store.getQuickMessages().filterNot { it.title == qm.title && it.body == qm.body })
                rebuild()
            })
            container.addView(row)
        }
        container.addView(button("+ Add quick message") { addQuickMessage() })
    }

    private fun addQuickMessage() {
        val title = editText("Titulo curto", "")
        val bodyField = editText("Texto completo", "")
        formDialog("Mensagem rapida", listOf("Titulo" to title, "Texto" to bodyField)) {
            val t = title.text.toString().trim()
            val b = bodyField.text.toString().trim()
            if (t.isBlank() || b.isBlank()) { toast("Preencha titulo e texto"); return@formDialog }
            store.setQuickMessages(store.getQuickMessages() + com.rokid.inbox.contracts.QuickMessage(t, b))
            rebuild()
        }
    }

    private fun addWhatsApp() {
        val serverUrl = editText("https://evolution.seudominio.com", "")
        val instance = editText("minha-instancia", "")
        val apiKey = editText("API Key", "", password = true)
        formDialog(
            "WhatsApp (Evolution API)",
            listOf("Server URL" to serverUrl, "Instance" to instance, "API Key" to apiKey),
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
        val token = editText("Personal Access Token", "", password = true)
        val query = editText("is:open is:pr involves:@me", "")
        formDialog(
            "GitHub PRs",
            listOf("Token" to token, "PR filter (optional)" to query),
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
        val clientId = editText("Client ID", "")
        val clientSecret = editText("Client Secret", "", password = true)
        val refreshToken = editText("Refresh Token", "", password = true)
        formDialog(
            "Gmail (somente leitura)",
            listOf("Client ID" to clientId, "Client Secret" to clientSecret, "Refresh Token" to refreshToken),
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
        val serverUrl = editText("https://seu-dominio:8787", "")
        val apiKey = editText("Bridge API Key", "", password = true)
        formDialog(
            "Telegram (bridge GramJS)",
            listOf("URL da bridge" to serverUrl, "API Key" to apiKey),
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
        toast("Testing connection...")
        Thread {
            val result = runCatching { runBlocking { buildProbe().ping() } }
            runOnUiThread {
                if (result.isSuccess) {
                    store.addBox(BoxConfig(InboxConfigStore.newBoxId(kind), kind, name, config))
                    InboxGraph.reloadFromConfig()
                    toast("Connected")
                    rebuild()
                } else {
                    toast("Failed: ${result.exceptionOrNull()?.message?.take(160)}")
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
            .setPositiveButton("Test & connect") { _, _ -> onSave() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /* ---------------- view helpers ---------------- */

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
