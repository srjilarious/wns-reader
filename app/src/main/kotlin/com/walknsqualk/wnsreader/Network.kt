// Copyright (c) 2026 Jeff DeWall
// SPDX-License-Identifier: GPL-3.0-or-later

package com.walknsqualk.wnsreader

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.time.Duration
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The HTTP side of AI questions and card audio. Zig decides what is sent
 * and reads what comes back; this only moves the bytes, so a coroutine
 * cancelled (the panel closed) cancels the call.
 */
object Network {
    // An AI answer can take a while: Opus thinks before it answers.
    private val client = OkHttpClient.Builder()
        .connectTimeout(Duration.ofSeconds(20))
        .readTimeout(Duration.ofMinutes(5))
        .callTimeout(Duration.ofMinutes(6))
        .build()

    /** The status and body of [request]. Cancelling the coroutine cancels the call. */
    suspend fun fetch(request: Request): Pair<Int, ByteArray> = client.newCall(request).await()

    /**
     * The body is read here, on OkHttp's own thread. Read after resuming,
     * it would be read on the caller's thread -- the main thread, for the
     * card screen -- and a body not already buffered then trips Android's
     * no-network-on-the-main-thread rule and kills the app.
     */
    private suspend fun Call.await(): Pair<Int, ByteArray> = suspendCancellableCoroutine { cont ->
        enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                val result = try {
                    response.use { it.code to it.body.bytes() }
                } catch (e: IOException) {
                    cont.resumeWithException(e)
                    return
                }
                cont.resume(result)
            }

            override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)
        })
        cont.invokeOnCancellation { cancel() }
    }

    fun md5Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }
}

/**
 * Android 17 (API 37) blocks connections to the local network unless the
 * app holds `ACCESS_LOCAL_NETWORK`, which matters for an Ollama server on
 * the LAN. Earlier Android (the Daylight's 13) has no such rule.
 */
object LocalNetwork {
    private const val FIRST_RESTRICTED_SDK = 37

    fun needsPermission(context: Context, url: String): Boolean {
        if (Build.VERSION.SDK_INT < FIRST_RESTRICTED_SDK) return false
        if (!isLocal(url)) return false
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED
    }

    /** Whether [url]'s host is on the local network: a private or link-local address, or a `.local` name. */
    fun isLocal(url: String): Boolean {
        val host = url.toHttpUrlOrNull()?.host ?: return false
        if (host.endsWith(".local")) return true
        val parts = host.split('.').mapNotNull { it.toIntOrNull() }
        if (parts.size == 4) {
            val (a, b) = parts
            return a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 169 && b == 254)
        }
        val v6 = host.lowercase()
        return v6.startsWith("fc") || v6.startsWith("fd") || v6.startsWith("fe80")
    }
}

/** What an AI question came back with. */
sealed interface AiOutcome {
    data class Answer(val text: String, val cached: Boolean) : AiOutcome
    data class Failure(val message: String) : AiOutcome
}

/** The bubble and its surroundings, as the panel sends them. */
data class AiQuestion(
    val dialog: String,
    val previous: String?,
    val next: String?,
    val highlight: String?,
    val title: String?,
    val page: Int?,
)

/**
 * Asks the configured provider about a bubble: native/src/assistant.zig
 * builds the request (or answers from the cache), OkHttp sends it, and
 * the Zig side parses and caches the reply.
 */
object AiClient {
    suspend fun ask(context: Context, prefs: Prefs, q: AiQuestion, fresh: Boolean = false): AiOutcome {
        val provider = prefs.provider()
        val endpoint = prefs.aiEndpoint(provider.id).ifEmpty { provider.endpoint }
        if (LocalNetwork.needsPermission(context, endpoint)) {
            return AiOutcome.Failure("$endpoint is on the local network. Allow local network access in Settings.")
        }
        val question = JSONObject()
            .put("provider", provider.id)
            .put("model", prefs.aiModel(provider.id))
            .put("endpoint", prefs.aiEndpoint(provider.id))
            .put("api_key", prefs.aiKey(provider.id))
            .put("style", prefs.aiStyle)
            .put("dialog", q.dialog)
            .put("use_cache", prefs.aiUseCache && !fresh)
        if (prefs.aiIncludeNeighbors) {
            q.previous?.let { question.put("previous", it) }
            q.next?.let { question.put("next", it) }
        }
        q.highlight?.let { question.put("highlight", it) }
        if (prefs.aiIncludeBookInfo) {
            q.title?.let { question.put("title", it) }
            q.page?.let { question.put("page", it) }
        }

        val prepared = try {
            NativeCore.prepareQuestion(question)
        } catch (e: NativeException) {
            return AiOutcome.Failure(
                when (e.message) {
                    "MissingApiKey" -> "No API key for ${provider.label}. Add one in Settings."
                    else -> "Couldn't build the request: ${e.message}"
                },
            )
        }
        if (prepared.getBoolean("cached")) return AiOutcome.Answer(prepared.getString("answer"), cached = true)

        val request = Request.Builder()
            .url(prepared.getString("url"))
            .post(prepared.getString("body").toRequestBody(JSON))
            .apply {
                val headers = prepared.getJSONArray("headers")
                for (i in 0 until headers.length()) {
                    val h = headers.getJSONObject(i)
                    header(h.getString("name"), h.getString("value"))
                }
            }
            .build()
        val (status, body) = try {
            Network.fetch(request)
        } catch (e: IOException) {
            return AiOutcome.Failure("Request failed: ${e.message ?: e.javaClass.simpleName}")
        }

        val finished = NativeCore.finishQuestion(
            JSONObject()
                .put("provider", provider.id)
                .put("model", prefs.aiModel(provider.id))
                .put("status", status)
                .put("body", body.toString(Charsets.UTF_8))
                .put("key", prepared.getString("key"))
                .put("title", q.title ?: "")
                .put("page", q.page ?: 0)
                .put("highlight", q.highlight ?: ""),
        )
        return if (finished.has("answer")) {
            AiOutcome.Answer(finished.getString("answer"), cached = false)
        } else {
            AiOutcome.Failure(finished.getString("failure"))
        }
    }

    private val JSON = "application/json".toMediaType()
}
