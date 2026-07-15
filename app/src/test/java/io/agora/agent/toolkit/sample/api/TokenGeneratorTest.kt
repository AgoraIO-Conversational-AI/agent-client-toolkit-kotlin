package io.agora.agent.toolkit.sample.api

import io.agora.dynamickey.media.AccessToken2
import io.agora.agent.toolkit.sample.KeyCenter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.InvocationTargetException

class TokenGeneratorTest {
    @Test
    fun appCertificateDoesNotComeFromExamplePlaceholder() {
        assertEquals(
            "",
            KeyCenter.APP_CERTIFICATE.takeIf { it == "your_agora_app_certificate" }.orEmpty()
        )
    }

    @Test
    fun buildConfigExposesOnlyAppCredentialsForTokenGeneration() {
        val fieldNames = Class.forName("io.agora.agent.toolkit.BuildConfig")
            .declaredFields
            .map { it.name }

        val tokenConfigFields = fieldNames
            .filter { it == "APP_ID" || it == "APP_CERTIFICATE" || it.contains("HOST") }
            .sorted()

        assertEquals(listOf("APP_CERTIFICATE", "APP_ID"), tokenConfigFields)
    }

    @Test
    fun generateLocalConvoAiToken_buildsRtcAndRtmAccessToken2() {
        val method = TokenGenerator::class.java.getDeclaredMethod(
            "generateLocalConvoAiToken",
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java
        )
        method.isAccessible = true

        val token = method.invoke(
            TokenGenerator,
            "0".repeat(32),
            "1".repeat(32),
            "channel_kotlin_123456",
            "123456"
        ) as String

        assertTrue(token.startsWith("007"))
        assertTrue(token.length > 80)
    }

    @Test
    fun generateLocalConvoAiToken_canBeParsedByJavaAccessToken2() {
        val method = TokenGenerator::class.java.getDeclaredMethod(
            "generateLocalConvoAiToken",
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java
        )
        method.isAccessible = true

        val token = method.invoke(
            TokenGenerator,
            "0".repeat(32),
            "1".repeat(32),
            "channel_kotlin_123456",
            "123456"
        ) as String

        val accessToken = AccessToken2()
        assertTrue(accessToken.parse(token))
    }

    @Test
    fun generateLocalConvoAiToken_rejectsBlankAppCertificate() {
        val method = TokenGenerator::class.java.getDeclaredMethod(
            "generateLocalConvoAiToken",
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java
        )
        method.isAccessible = true

        val error = assertThrows(InvocationTargetException::class.java) {
            method.invoke(
                TokenGenerator,
                "0".repeat(32),
                "",
                "channel_kotlin_123456",
                "123456"
            )
        }
        assertTrue(error.cause is IllegalArgumentException)
    }

    @Test
    fun generateLocalConvoAiToken_rejectsBlankUid() {
        val method = TokenGenerator::class.java.getDeclaredMethod(
            "generateLocalConvoAiToken",
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java
        )
        method.isAccessible = true

        val error = assertThrows(InvocationTargetException::class.java) {
            method.invoke(
                TokenGenerator,
                "0".repeat(32),
                "1".repeat(32),
                "channel_kotlin_123456",
                ""
            )
        }
        assertTrue(error.cause is IllegalArgumentException)
    }

}
