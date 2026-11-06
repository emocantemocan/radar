package com.radarnav.core.net

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class HttpException(val code: Int, message: String) : IOException(message)

val LenientJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
}

suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            cont.resume(response)
        }
    })
    cont.invokeOnCancellation { runCatching { cancel() } }
}

/** İsteği gönderir, 2xx değilse [HttpException] fırlatır, gövdeyi metin olarak döner. */
suspend fun OkHttpClient.fetchString(request: Request): String {
    newCall(request).await().use { resp ->
        val body = resp.body?.string().orEmpty()
        if (!resp.isSuccessful) {
            throw HttpException(resp.code, "HTTP ${resp.code}: ${body.take(300)}")
        }
        return body
    }
}

suspend fun OkHttpClient.fetchJson(request: Request): JsonElement =
    LenientJson.parseToJsonElement(fetchString(request))

// --- Esnek JSON okuma yardımcıları (API'ler alan tiplerinde tutarsız olabiliyor) ---

fun JsonElement?.obj(): JsonObject? = this as? JsonObject
fun JsonElement?.arr(): JsonArray? = this as? JsonArray

fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)
    ?.takeUnless { it is JsonNull }?.content

fun JsonObject.dbl(key: String): Double? = (this[key] as? JsonPrimitive)
    ?.takeUnless { it is JsonNull }?.let { it.doubleOrNull ?: it.content.replace(',', '.').toDoubleOrNull() }

fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)
    ?.takeUnless { it is JsonNull }?.let { it.intOrNull ?: it.doubleOrNull?.toInt() ?: it.content.toIntOrNull() }

fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)
    ?.takeUnless { it is JsonNull }?.booleanOrNull

/** Büyük/küçük harf duyarsız alan arama ("Radars" / "radars"). */
fun JsonObject.anyCase(key: String): JsonElement? =
    this[key] ?: entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value
