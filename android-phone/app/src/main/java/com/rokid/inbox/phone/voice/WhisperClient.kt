package com.rokid.inbox.phone.voice

import com.google.gson.JsonParser
import com.rokid.inbox.phone.channels.Http
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * OpenAI Whisper transcription. The key stays on-device; there is no backend
 * proxy. Ported from even-inbox `voice.ts` transcribeAudio.
 */
class WhisperClient(private val apiKey: String) {
    val isConfigured: Boolean get() = apiKey.isNotBlank()

    fun transcribe(wav: ByteArray, language: String = "pt"): String {
        require(apiKey.isNotBlank()) { "OpenAI key not configured" }
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", "voice.wav", wav.toRequestBody("audio/wav".toMediaType()))
            .addFormDataPart("model", "whisper-1")
            .addFormDataPart("language", language)
            .build()
        val request = Request.Builder()
            .url("https://api.openai.com/v1/audio/transcriptions")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(body)
            .build()
        Http.client.newCall(request).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) {
                throw RuntimeException("Whisper API ${res.code}: ${text.take(300)}")
            }
            val json = JsonParser.parseString(text).asJsonObject
            return json.get("text")?.asString?.trim().orEmpty()
        }
    }
}
