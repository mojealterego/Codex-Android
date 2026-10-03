package com.mojealterego.codexandroid.agent

import com.google.gson.Gson
import java.io.IOException
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class AgentBffClient(
    baseUrl: String,
    private val httpClient: OkHttpClient,
    private val gson: Gson = Gson()
) {
    private val baseUrl: HttpUrl = normalizeBaseUrl(baseUrl)

    suspend fun startSession(
        requestBody: StartAgentSessionRequest,
        idempotencyKey: String
    ): AgentSessionResponse = withContext(Dispatchers.IO) {
        require(idempotencyKey.isNotBlank()) { "Idempotency key is required" }

        val request = Request.Builder()
            .url(endpoint("v1/agents/sessions"))
            .header("Accept", "application/json")
            .header("Idempotency-Key", idempotencyKey.trim())
            .post(
                gson.toJson(requestBody).toRequestBody(
                    JSON_MEDIA_TYPE
                )
            )
            .build()

        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException(
                    "Agent BFF session start failed: HTTP " +
                        response.code + errorSuffix(body)
                )
            }
            gson.fromJson(body, AgentSessionResponse::class.java)
                ?: throw IOException("Agent BFF returned an empty session response")
        }
    }

    suspend fun loadChanges(sessionId: String): AgentChangeSetResponse =
        withContext(Dispatchers.IO) {
            require(sessionId.isNotBlank()) { "Session id is required" }

            val request = Request.Builder()
                .url(
                    endpoint(
                        "v1/agents/sessions/" +
                            sessionId.trim() +
                            "/changes"
                    )
                )
                .header("Accept", "application/json")
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IOException(
                        "Agent BFF changes request failed: HTTP " +
                            response.code + errorSuffix(body)
                    )
                }
                gson.fromJson(body, AgentChangeSetResponse::class.java)
                    ?: throw IOException(
                        "Agent BFF returned an empty changes response"
                    )
            }
        }

    suspend fun streamEvents(
        sessionId: String,
        onEvent: (AgentStreamEvent) -> Unit
    ) = withContext(Dispatchers.IO) {
        require(sessionId.isNotBlank()) { "Session id is required" }

        val request = Request.Builder()
            .url(
                endpoint(
                    "v1/agents/sessions/" +
                        sessionId.trim() +
                        "/events"
                )
            )
            .header("Accept", "text/event-stream")
            .get()
            .build()

        val call = httpClient.newCall(request)
        val cancellation = coroutineContext.job.invokeOnCompletion {
            call.cancel()
        }

        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    val body = response.body?.string().orEmpty()
                    throw IOException(
                        "Agent BFF event stream failed: HTTP " +
                            response.code + errorSuffix(body)
                    )
                }

                val source = response.body?.source()
                    ?: throw IOException("Agent BFF event stream has no body")
                val decoder = AgentSseDecoder()

                while (true) {
                    coroutineContext.ensureActive()
                    val line = source.readUtf8Line() ?: break
                    decoder.accept(line)?.let(onEvent)
                }
            }
        } finally {
            cancellation.dispose()
        }
    }

    private fun endpoint(relativePath: String): HttpUrl =
        baseUrl.resolve(relativePath)
            ?: throw IllegalArgumentException("Invalid Agent BFF endpoint")

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private fun normalizeBaseUrl(value: String): HttpUrl {
            val trimmed = value.trim()
            require(trimmed.isNotBlank()) { "Agent BFF URL is required" }

            val normalized = if (trimmed.endsWith("/")) trimmed else "$trimmed/"
            val url = normalized.toHttpUrl()

            val localhost = url.host in setOf(
                "localhost",
                "127.0.0.1",
                "10.0.2.2"
            )
            require(url.isHttps || localhost) {
                "Agent BFF must use HTTPS outside localhost"
            }
            return url
        }

        private fun errorSuffix(body: String): String {
            val clean = body.trim().replace("\n", " ")
            return if (clean.isBlank()) "" else ": " + clean.take(500)
        }
    }
}
