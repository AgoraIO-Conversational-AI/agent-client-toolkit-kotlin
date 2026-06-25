package io.agora.agent.toolkit.sample.api

import okhttp3.Request
import io.agora.agent.toolkit.sample.KeyCenter
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenGeneratorTest {
    @Test
    fun buildHttpRequest_usesConvoAiToolboxTokenEndpointWithoutAuthorizationHeader() {
        val method = TokenGenerator::class.java.getDeclaredMethod(
            "buildHttpRequest",
            JSONObject::class.java
        )
        method.isAccessible = true

        val request = method.invoke(
            TokenGenerator,
            JSONObject().put("uid", "123456")
        ) as Request

        assertEquals(
            "${KeyCenter.TOOLBOX_SERVER_HOST.trimEnd('/')}/v2/token/generate",
            request.url.toString()
        )
        assertNull(request.header("Authorization"))
    }

    @Test
    fun buildJsonRequest_usesConvoAiTokenPayloadShape() {
        val method = TokenGenerator::class.java.getDeclaredMethod(
            "buildJsonRequest",
            String::class.java,
            String::class.java,
            Array<AgoraTokenType>::class.java
        )
        method.isAccessible = true

        val payload = method.invoke(
            TokenGenerator,
            "",
            "123456",
            arrayOf(AgoraTokenType.Rtc, AgoraTokenType.Rtm)
        ) as JSONObject

        assertEquals("", payload.getString("channelName"))
        assertEquals("123456", payload.getString("uid"))
        assertEquals("Android", payload.getString("src"))
        assertEquals(24 * 60 * 60, payload.getInt("expire"))

        val types = payload.get("types") as JSONArray
        assertEquals(2, types.length())
        assertEquals(1, types.getInt(0))
        assertEquals(2, types.getInt(1))
        assertTrue(payload.has("appId"))
        assertEquals(KeyCenter.APP_CERTIFICATE.isNotEmpty(), payload.has("appCertificate"))
    }

    @Test
    fun appCertificateDoesNotComeFromExamplePlaceholder() {
        assertEquals(
            "",
            KeyCenter.APP_CERTIFICATE.takeIf { it == "your_agora_app_certificate" }.orEmpty()
        )
    }

    @Test
    fun toolboxHostIsExposedThroughBuildConfig() {
        val fieldNames = Class.forName("io.agora.agent.toolkit.BuildConfig")
            .declaredFields
            .map { it.name }

        assertTrue(fieldNames.contains("TOOLBOX_SERVER_HOST"))
    }
}
