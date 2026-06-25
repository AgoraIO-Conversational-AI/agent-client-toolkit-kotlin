package io.agora.agent.toolkit.sample.api

import io.agora.agent.toolkit.sample.KeyCenter
import io.agora.agent.toolkit.sample.api.net.SecureOkHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

sealed class AgoraTokenType(val value: Int) {
    data object Rtc : AgoraTokenType(1)
    data object Rtm : AgoraTokenType(2)
}

/**
 * ⚠️ WARNING: DO NOT USE IN PRODUCTION ⚠️
 * 
 * This TokenGenerator is for DEMO/DEVELOPMENT purposes ONLY.
 * 
 * **CRITICAL SECURITY WARNING:**
 * - This class directly exposes your App ID and App Certificate in client-side code
 * - The token generation endpoint is a demo toolbox service and may be shut down at any time
 * - Using this in production will expose your credentials and cause security vulnerabilities
 * - If the demo service is shut down, your production app will break
 * 
 * **PRODUCTION REQUIREMENTS:**
 * - Token generation MUST be done on your own secure backend server
 * - Never expose App Certificate in client-side code
 * - Implement proper authentication and authorization on your server
 * - Use HTTPS for all token generation requests
 * 
 * Do not use this endpoint for real business traffic.
 * If the demo service is retired, production apps that depend on it will break.
 * This endpoint is only for demos and development tests.
 */
object TokenGenerator {
    private const val DEFAULT_EXPIRE_SECONDS = 60L * 60 * 24

    private val okHttpClient: OkHttpClient by lazy {
        SecureOkHttpClient.create()
            .build()
    }

    /**
     * Generate RTC/RTM tokens asynchronously (DEMO ONLY - DO NOT USE IN PRODUCTION)
     * 
     * ⚠️ WARNING: This method uses a demo token service and exposes credentials in client code.
     * For production, implement token generation on your own secure backend server.
     * 
     * @return Result containing the token string on success, or failure with exception
     */
    suspend fun generateTokensAsync(
        channelName: String,
        uid: String,
        tokenTypes: Array<AgoraTokenType> = arrayOf(AgoraTokenType.Rtc, AgoraTokenType.Rtm)
    ): Result<String> = withContext(Dispatchers.Main) {
        try {
            Result.success(fetchToken(channelName, uid, tokenTypes))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun fetchToken(
        channelName: String,
        uid: String,
        tokenTypes: Array<AgoraTokenType>
    ): String = withContext(Dispatchers.IO) {
        val postBody = buildJsonRequest(channelName, uid, tokenTypes)
        val request = buildHttpRequest(postBody)

        executeRequest(request)
    }

    private fun buildJsonRequest(
        channelName: String,
        uid: String,
        tokenTypes: Array<AgoraTokenType>
    ): JSONObject = JSONObject().apply {
        put("appId", KeyCenter.APP_ID)
        if (KeyCenter.APP_CERTIFICATE.isNotEmpty()) {
            put("appCertificate", KeyCenter.APP_CERTIFICATE)
        }
        put("channelName", channelName)
        put("expire", DEFAULT_EXPIRE_SECONDS)
        put("src", "Android")
        put("ts", System.currentTimeMillis().toString())

        when (tokenTypes.size) {
            1 -> put("type", tokenTypes[0].value)
            else -> put("types", JSONArray(tokenTypes.map { it.value }))
        }

        put("uid", uid)
    }

    private fun buildHttpRequest(postBody: JSONObject): Request {
        // ⚠️ WARNING: This is a DEMO endpoint - DO NOT use in production
        val url = "${KeyCenter.TOOLBOX_SERVER_HOST.trimEnd('/')}/v2/token/generate"

        return Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
            .post(postBody.toString().toRequestBody())
            .build()
    }

    private fun executeRequest(request: Request): String {
        val response = okHttpClient.newCall(request).execute()

        if (!response.isSuccessful) {
            throw RuntimeException("Fetch token error: httpCode=${response.code}, httpMsg=${response.message}")
        }

        val body = response.body?.string() ?: throw RuntimeException("Response body is null")
        val bodyJson = JSONObject(body)
        if (bodyJson.optInt("code", -1) != 0) {
            throw RuntimeException(
                "Fetch token error: httpCode=${response.code}, " +
                        "httpMsg=${response.message}, " +
                        "reqCode=${bodyJson.opt("code")}, " +
                        "reqMsg=${bodyJson.opt("message")}"
            )
        }
        return (bodyJson.getJSONObject("data")).getString("token")
    }
}
