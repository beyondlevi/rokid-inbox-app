package com.rokid.inbox.phone.channels

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Shared HTTP plumbing + tiny JSON helpers for the REST-backed channels. */
object Http {
    val gson: Gson = Gson()

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    fun parse(json: String?): JsonElement? =
        if (json.isNullOrBlank()) null else runCatching { com.google.gson.JsonParser.parseString(json) }.getOrNull()
}

fun JsonElement?.obj(): JsonObject? = this as? JsonObject
fun JsonElement?.arr(): JsonArray? = this as? JsonArray

fun JsonObject?.str(key: String): String {
    val v = this?.get(key) ?: return ""
    return if (v.isJsonPrimitive) v.asString else ""
}

fun JsonObject?.optObj(key: String): JsonObject? = this?.get(key) as? JsonObject
fun JsonObject?.optArr(key: String): JsonArray? = this?.get(key) as? JsonArray

fun JsonObject?.longOrNull(key: String): Long? {
    val v = this?.get(key) ?: return null
    return runCatching {
        when {
            v.isJsonPrimitive && v.asJsonPrimitive.isNumber -> v.asLong
            v.isJsonPrimitive && v.asJsonPrimitive.isString && v.asString.matches(Regex("\\d+")) -> v.asString.toLong()
            else -> null
        }
    }.getOrNull()
}

fun JsonObject?.intOrNull(key: String): Int? = this.longOrNull(key)?.toInt()
