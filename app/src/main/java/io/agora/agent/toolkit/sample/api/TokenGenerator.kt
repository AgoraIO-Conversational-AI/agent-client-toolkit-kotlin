package io.agora.agent.toolkit.sample.api

import io.agora.dynamickey.media.RtcTokenBuilder2
import io.agora.agent.toolkit.sample.KeyCenter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ⚠️ WARNING: DO NOT USE IN PRODUCTION ⚠️
 * 
 * This TokenGenerator is for DEMO/DEVELOPMENT purposes ONLY.
 * 
 * **CRITICAL SECURITY WARNING:**
 * - This class directly exposes your App ID and App Certificate in client-side code
 * - Local token generation directly exposes your App ID and App Certificate in client-side code
 * - Using this in production will expose your credentials and cause security vulnerabilities
 * 
 * **PRODUCTION REQUIREMENTS:**
 * - Token generation MUST be done on your own secure backend server
 * - Never expose App Certificate in client-side code
 * - Implement proper authentication and authorization on your server
 * - Use HTTPS for all token generation requests
 * 
 * This generator is only for demos and development tests.
 */
object TokenGenerator {
    private const val DEFAULT_EXPIRE_SECONDS = 60L * 60 * 24

    /**
     * Generate RTC/RTM tokens asynchronously (DEMO ONLY - DO NOT USE IN PRODUCTION)
     * 
     * ⚠️ WARNING: This method generates tokens locally from APP_CERTIFICATE.
     * It is for demos only.
     * For production, implement token generation on your own secure backend server.
     * 
     * @return Result containing the token string on success, or failure with exception
     */
    suspend fun generateTokensAsync(
        channelName: String,
        uid: String
    ): Result<String> = withContext(Dispatchers.Main) {
        try {
            Result.success(fetchToken(channelName, uid))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun fetchToken(
        channelName: String,
        uid: String
    ): String = withContext(Dispatchers.Default) {
        generateLocalConvoAiToken(
            appId = KeyCenter.APP_ID,
            appCertificate = KeyCenter.APP_CERTIFICATE,
            channelName = channelName,
            uid = uid
        )
    }

    private fun generateLocalConvoAiToken(
        appId: String,
        appCertificate: String,
        channelName: String,
        uid: String
    ): String {
        require(appCertificate.isNotBlank()) {
            "APP_CERTIFICATE is required for local ConvoAI token generation"
        }
        require(uid.isNotBlank() && uid.all { it.isDigit() }) {
            "uid must be numeric when auto-generating a ConvoAI token"
        }

        val tokenExpire = DEFAULT_EXPIRE_SECONDS.toInt()
        val token = RtcTokenBuilder2().buildTokenWithRtm(
            appId,
            appCertificate,
            channelName,
            uid,
            RtcTokenBuilder2.Role.ROLE_PUBLISHER,
            tokenExpire,
            tokenExpire
        )
        require(token.isNotBlank()) {
            "Failed to generate ConvoAI token with Java token2 implementation"
        }
        return token
    }
}
