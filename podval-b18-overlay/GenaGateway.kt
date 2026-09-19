package ru.dachafibonacci.podval.gena

import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** Minimal contract with local Gena gateway: POST /api/chat {message}. */
data class GenaReply(val text: String)

data class GatewayHttpResponse(val code: Int, val body: String)

fun interface GenaTransport {
    fun post(endpoint: String, payload: String): GatewayHttpResponse
}

/**
 * Dedicated transport for Gena.
 *
 * B17 used Android HttpURLConnection. Physical testing over Tailscale Serve showed
 * that the request reached Gena and the model produced the reply, but the Android
 * client intermittently received `connection closed` / `Software caused connection abort`
 * while the same POST worked from PowerShell through the same HTTPS endpoint.
 *
 * OkHttp is used explicitly here and pinned to HTTP/1.1 for this endpoint. Gena's
 * Python BaseHTTP server answers HTTP/1.0 behind Tailscale Serve, so avoiding HTTP/2
 * negotiation plus closing the request connection gives us a conservative transport
 * path without changing the Gena API contract.
 */
private class OkHttpGenaTransport(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .callTimeout(195, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .protocols(listOf(Protocol.HTTP_1_1))
        .build(),
) : GenaTransport {
    override fun post(endpoint: String, payload: String): GatewayHttpResponse {
        val request = Request.Builder()
            .url(endpoint)
            .header("Accept", "application/json")
            .header("Connection", "close")
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        return client.newCall(request).execute().use { response ->
            GatewayHttpResponse(
                code = response.code,
                body = response.body?.string().orEmpty(),
            )
        }
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

class GenaGateway(
    private val transport: GenaTransport = OkHttpGenaTransport(),
) {
    fun chat(endpoint: String, message: String): GenaReply {
        val payload = JSONObject().put("message", message).toString()
        val response = transport.post(endpoint, payload)
        val code = response.code
        val body = response.body

        val root = runCatching { JSONObject(body) }.getOrElse {
            throw IOException("Гена вернул не JSON (HTTP $code)")
        }
        val data = root.optJSONObject("data") ?: root
        if (code !in 200..299 || !data.optBoolean("ok", code in 200..299)) {
            val detail = data.optString("error").ifBlank { "HTTP $code" }
            throw IOException("Ошибка Гены: $detail")
        }
        val reply = data.optString("reply").trim()
        if (reply.isBlank()) throw IOException("Гена вернул пустой ответ")
        return GenaReply(reply)
    }
}
