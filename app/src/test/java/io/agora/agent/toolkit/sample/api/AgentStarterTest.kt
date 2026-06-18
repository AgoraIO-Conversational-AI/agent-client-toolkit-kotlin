package io.agora.agent.toolkit.sample.api

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AgentStarterTest {
    @Test
    fun buildJsonPayload_omitsLlmAndTtsConfiguration() {
        val method = AgentStarter::class.java.getDeclaredMethod(
            "buildJsonPayload",
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            List::class.java
        )
        method.isAccessible = true

        val payload = method.invoke(
            AgentStarter,
            "channel_kotlin_123456",
            "channel_kotlin_123456",
            "123456",
            "token-value",
            listOf("654321")
        ) as JSONObject

        assertEquals("deepgram_nova_3", payload.getString("preset"))

        val properties = payload.getJSONObject("properties")
        assertFalse(properties.has("llm"))
        assertFalse(properties.has("tts"))
    }
}
