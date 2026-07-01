package com.rokid.inbox.phone

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.rokid.inbox.contracts.ChannelKind
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

/** A configured account/inbox plus its channel-specific credentials. */
data class BoxConfig(
    val id: String,
    val kind: ChannelKind,
    val name: String,
    val config: Map<String, String> = emptyMap(),
)

/**
 * On-device credential storage backed by EncryptedSharedPreferences. Mirrors
 * even-inbox `storage.ts` (boxes list + global OpenAI key). Nothing leaves the
 * phone.
 */
class InboxConfigStore(context: Context) {
    private val gson = Gson()
    private val prefs: SharedPreferences = createPrefs(context.applicationContext)

    fun getBoxes(): List<BoxConfig> {
        val raw = prefs.getString(KEY_BOXES, null) ?: return emptyList()
        return runCatching {
            gson.fromJson<List<BoxConfig>>(raw, object : TypeToken<List<BoxConfig>>() {}.type)
        }.getOrNull() ?: emptyList()
    }

    fun saveBoxes(list: List<BoxConfig>) {
        prefs.edit().putString(KEY_BOXES, gson.toJson(list)).apply()
    }

    fun addBox(box: BoxConfig) = saveBoxes(getBoxes() + box)

    fun renameBox(id: String, name: String) =
        saveBoxes(getBoxes().map { if (it.id == id) it.copy(name = name) else it })

    fun deleteBox(id: String) = saveBoxes(getBoxes().filterNot { it.id == id })

    fun getOpenAiKey(): String = prefs.getString(KEY_OPENAI, "").orEmpty()

    fun setOpenAiKey(key: String) {
        prefs.edit().putString(KEY_OPENAI, key.trim()).apply()
    }

    fun getQuickMessages(): List<com.rokid.inbox.contracts.QuickMessage> {
        val raw = prefs.getString(KEY_QUICK, null) ?: return DEFAULT_QUICK
        return runCatching {
            gson.fromJson<List<com.rokid.inbox.contracts.QuickMessage>>(
                raw,
                object : TypeToken<List<com.rokid.inbox.contracts.QuickMessage>>() {}.type,
            )
        }.getOrNull()?.filter { it.title.isNotBlank() && it.body.isNotBlank() } ?: DEFAULT_QUICK
    }

    fun setQuickMessages(list: List<com.rokid.inbox.contracts.QuickMessage>) {
        prefs.edit().putString(KEY_QUICK, gson.toJson(list)).apply()
    }

    private fun createPrefs(context: Context): SharedPreferences =
        runCatching {
            val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            EncryptedSharedPreferences.create(
                "inbox_secure_prefs",
                masterKeyAlias,
                context,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }.getOrElse {
            Log.w(TAG, "EncryptedSharedPreferences unavailable, falling back to plain prefs", it)
            context.getSharedPreferences("inbox_prefs", Context.MODE_PRIVATE)
        }

    companion object {
        private const val TAG = "InboxConfigStore"
        private const val KEY_BOXES = "boxes.v1"
        private const val KEY_OPENAI = "openai.key"
        private const val KEY_QUICK = "quick.v1"

        private val DEFAULT_QUICK = listOf(
            com.rokid.inbox.contracts.QuickMessage("Estou chegando", "Oi! Estou chegando, ja te encontro."),
            com.rokid.inbox.contracts.QuickMessage("Estou atrasado", "Desculpa, estou atrasado. Chego assim que puder."),
            com.rokid.inbox.contracts.QuickMessage("Te ligo ja", "Agora nao consigo falar, te ligo em instantes."),
            com.rokid.inbox.contracts.QuickMessage("Ok", "Ok!"),
        )

        fun newBoxId(kind: ChannelKind): String =
            "${kind.name.lowercase()}-${System.currentTimeMillis().toString(36)}${(0..9999).random().toString(36)}"
    }
}
