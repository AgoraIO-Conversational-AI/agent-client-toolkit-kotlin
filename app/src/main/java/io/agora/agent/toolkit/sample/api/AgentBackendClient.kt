package io.agora.agent.toolkit.sample.api

import io.agora.agent.toolkit.sample.api.net.SecureOkHttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

data class AgentBackendConfig(
    val appId: String,
    val token: String,
    val userUid: Int,
    val agentUid: Int,
    val channelName: String,
)

class AgentBackendException(
    message: String,
    val httpCode: Int? = null,
    val backendCode: Int? = null,
) : RuntimeException(message)

class AgentBackendClient(
    backendUrl: String,
    private val client: OkHttpClient = SecureOkHttpClient.create().build(),
) {
    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val BACKEND_CLEANUP_SCOPE = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    private val baseUrl: HttpUrl = backendUrl.trim().toHttpUrlOrNull()
        ?: throw IllegalArgumentException("Invalid agent backend URL: $backendUrl")

    suspend fun getConfig(channelName: String, userUid: Int): Result<AgentBackendConfig> =
        runSuspendCatching {
            withContext(Dispatchers.IO) {
                val url = endpoint("get_config").newBuilder()
                    .addQueryParameter("channel", channelName)
                    .addQueryParameter("uid", userUid.toString())
                    .build()
                val data = executeForData(Request.Builder().url(url).get().build())
                    ?: throw AgentBackendException("Backend config response has no data")

                AgentBackendConfig(
                    appId = data.requireString("app_id"),
                    token = data.requireString("token"),
                    userUid = data.requirePositiveInt("uid"),
                    agentUid = data.requirePositiveInt("agent_uid"),
                    channelName = data.requireString("channel_name"),
                )
            }
        }

    suspend fun startAgent(
        channelName: String,
        agentUid: Int,
        userUid: Int,
        sosDetectionMode: TurnDetectionMode,
        eosDetectionMode: TurnDetectionMode,
    ): Result<String> {
        val request = BACKEND_CLEANUP_SCOPE.async {
            runSuspendCatching {
                val body = JSONObject().apply {
                    put("channelName", channelName)
                    put("agentUid", agentUid)
                    put("userUid", userUid)
                    put("startOfSpeechMode", sosDetectionMode.value)
                    put("endOfSpeechMode", eosDetectionMode.value)
                }
                val request = Request.Builder()
                    .url(endpoint("startAgent"))
                    .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .build()
                val data = executeForData(request)
                    ?: throw AgentBackendException("Backend start response has no data")
                data.requireString("agent_id")
            }
        }

        return try {
            request.await()
        } catch (cancellation: CancellationException) {
            BACKEND_CLEANUP_SCOPE.launch {
                request.await().getOrNull()?.let { stopAgent(it) }
            }
            throw cancellation
        }
    }

    suspend fun stopAgent(agentId: String): Result<Unit> = runSuspendCatching {
        withContext(Dispatchers.IO) {
            val body = JSONObject().put("agentId", agentId)
            val request = Request.Builder()
                .url(endpoint("stopAgent"))
                .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
            executeForData(request)
            Unit
        }
    }

    fun stopAgentBestEffort(
        agentId: String,
        completion: (Result<Unit>) -> Unit = {},
    ) {
        BACKEND_CLEANUP_SCOPE.launch {
            completion(stopAgent(agentId))
        }
    }

    private fun endpoint(path: String): HttpUrl = baseUrl.newBuilder()
        .addPathSegment(path)
        .build()

    private fun executeForData(request: Request): JSONObject? {
        client.newCall(request).execute().use { response ->
            val rawBody = response.body.string()
            val envelope = rawBody.toJsonOrNull()
            val backendMessage = envelope?.optNonBlankString("msg")
                ?: envelope?.optNonBlankString("detail")
                ?: response.message.takeIf { it.isNotBlank() }
                ?: "Backend request failed"

            if (!response.isSuccessful) {
                throw AgentBackendException(
                    message = "Backend request failed (HTTP ${response.code}): $backendMessage",
                    httpCode = response.code,
                    backendCode = envelope?.optInt("code")?.takeIf { it != 0 },
                )
            }
            if (envelope == null) {
                throw AgentBackendException("Backend returned invalid JSON")
            }

            val code = envelope.optInt("code", Int.MIN_VALUE)
            if (code != 0) {
                throw AgentBackendException(
                    message = "Backend request failed (code $code): $backendMessage",
                    backendCode = code,
                )
            }
            return envelope.optJSONObject("data")
        }
    }

    private fun String.toJsonOrNull(): JSONObject? = try {
        JSONObject(this)
    } catch (_: Exception) {
        null
    }

    private fun JSONObject.optNonBlankString(key: String): String? =
        optString(key, "").trim().takeIf { it.isNotEmpty() && it != "null" }

    private fun JSONObject.requireString(key: String): String =
        optNonBlankString(key)
            ?: throw AgentBackendException("Backend response is missing $key")

    private fun JSONObject.requirePositiveInt(key: String): Int {
        val value = opt(key)?.toString()?.toIntOrNull()
        if (value == null || value <= 0) {
            throw AgentBackendException("Backend response has invalid $key")
        }
        return value
    }
}

private suspend fun <T> runSuspendCatching(block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (throwable: Throwable) {
        Result.failure(throwable)
    }
