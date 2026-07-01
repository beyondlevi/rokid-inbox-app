package com.rokid.inbox.phone.ai

import android.util.Base64
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.rokid.inbox.phone.channels.Http
import com.rokid.inbox.phone.channels.obj
import com.rokid.inbox.phone.channels.optArr
import com.rokid.inbox.phone.channels.optObj
import com.rokid.inbox.phone.channels.str
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * OpenAI-powered descriptions. Uses the same API key as Whisper transcription.
 *
 * - Images: Vision via Chat Completions (data-URI image).
 * - Files (pdf/xlsx/csv/docx/...): upload to the Files API, then a Responses API
 *   call with the code-interpreter tool that reads the file and summarizes it.
 */
class AiDescriber(private val apiKey: String) {
    val isConfigured: Boolean get() = apiKey.isNotBlank()

    fun describeImage(jpeg: ByteArray, language: String = "en"): String {
        require(apiKey.isNotBlank()) { "OpenAI key not configured" }
        val dataUri = "data:image/jpeg;base64," + Base64.encodeToString(jpeg, Base64.NO_WRAP)
        val content = JsonArray().apply {
            add(JsonObject().apply { addProperty("type", "text"); addProperty("text", imagePrompt(language)) })
            add(JsonObject().apply {
                addProperty("type", "image_url")
                add("image_url", JsonObject().apply { addProperty("url", dataUri) })
            })
        }
        val body = JsonObject().apply {
            addProperty("model", MODEL)
            add("messages", JsonArray().apply {
                add(JsonObject().apply { addProperty("role", "user"); add("content", content) })
            })
            addProperty("max_tokens", 700)
        }
        val json = post("https://api.openai.com/v1/chat/completions", body.toString())
        val choice = Http.parse(json).obj().optArr("choices")?.firstOrNull() as? JsonObject
        val text = choice.optObj("message").str("content")
        return text.ifBlank { "(sem descrição)" }
    }

    fun describeFile(bytes: ByteArray, fileName: String, language: String = "en"): String {
        require(apiKey.isNotBlank()) { "OpenAI key not configured" }
        val name = fileName.ifBlank { "file" }
        val fileId = uploadFile(bytes, name)
        val body = JsonObject().apply {
            addProperty("model", MODEL)
            add("tools", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("type", "code_interpreter")
                    add("container", JsonObject().apply {
                        addProperty("type", "auto")
                        add("file_ids", JsonArray().apply { add(fileId) })
                    })
                })
            })
            addProperty("input", "${filePrompt(language)} ($name)")
        }
        val json = post("https://api.openai.com/v1/responses", body.toString())
        return parseResponsesText(json).ifBlank { "(sem descrição)" }
    }

    /* ---------------- OpenAI helpers ---------------- */

    private fun uploadFile(bytes: ByteArray, fileName: String): String {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("purpose", "assistants")
            .addFormDataPart("file", fileName, bytes.toRequestBody("application/octet-stream".toMediaType()))
            .build()
        val request = Request.Builder()
            .url("https://api.openai.com/v1/files")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(body)
            .build()
        Http.client.newCall(request).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) throw RuntimeException("OpenAI files ${res.code}: ${text.take(300)}")
            val id = Http.parse(text).obj().str("id")
            if (id.isBlank()) throw RuntimeException("OpenAI files: no id returned")
            return id
        }
    }

    private fun post(url: String, jsonBody: String): String {
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .build()
        Http.client.newCall(request).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) throw RuntimeException("OpenAI ${res.code}: ${text.take(400)}")
            return text
        }
    }

    private fun parseResponsesText(json: String): String {
        val root = Http.parse(json).obj()
        root.str("output_text").let { if (it.isNotBlank()) return it }
        val out = root.optArr("output") ?: JsonArray()
        val sb = StringBuilder()
        for (item in out) {
            val io = item as? JsonObject ?: continue
            if (io.str("type") != "message") continue
            val content = io.optArr("content") ?: continue
            for (c in content) {
                val co = c as? JsonObject ?: continue
                if (co.str("type") == "output_text") sb.append(co.str("text"))
            }
        }
        return sb.toString().trim()
    }

    private fun imagePrompt(language: String): String = if (isPt(language)) {
        "Descreva em portugues, de forma detalhada, o que aparece nesta imagem: elementos, texto visivel, contexto."
    } else {
        "Describe in English, in detail, what appears in this image: elements, visible text, and context."
    }

    private fun filePrompt(language: String): String = if (isPt(language)) {
        "Use a ferramenta python (code interpreter) para abrir e ler o arquivo anexado e depois descreva em " +
            "portugues, de forma detalhada, do que se trata: tipo, estrutura e principais informacoes/dados. " +
            "Se for planilha, resuma abas e colunas; se for pdf/documento, resuma as secoes; se for imagem, descreva o conteudo."
    } else {
        "Use the python tool (code interpreter) to open and read the attached file, then describe it in English, " +
            "in detail: type, structure and the main information/data. If it is a spreadsheet, summarize sheets and " +
            "columns; if it is a pdf/document, summarize the sections; if it is an image, describe the content."
    }

    private fun isPt(language: String): Boolean = language.lowercase().startsWith("pt")

    private companion object {
        private const val MODEL = "gpt-4o-mini"
    }
}
