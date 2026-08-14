package io.agora.conversational.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakThinkTest {
    @Test
    fun publicApiExposesSpeakAndThink() {
        val methods = IConversationalAIAPI::class.java.methods

        val speak = methods.first { it.name == "speak" }
        val think = methods.first { it.name == "think" }

        assertEquals(3, speak.parameterCount)
        assertEquals(String::class.java, speak.parameterTypes[0])
        assertEquals(SpeakMessage::class.java, speak.parameterTypes[1])
        assertEquals(3, think.parameterCount)
        assertEquals(String::class.java, think.parameterTypes[0])
        assertEquals(ThinkMessage::class.java, think.parameterTypes[1])
    }

    @Test
    fun speakPayloadReusesPriority() {
        val payload = buildSpeakPayload(
            SpeakMessage(
                text = "Please pay attention",
                priority = Priority.APPEND,
                interruptable = false
            )
        )

        assertEquals("Please pay attention", payload.getString("message"))
        assertEquals("APPEND", payload.getString("priority"))
        assertFalse(payload.getBoolean("interruptable"))
    }

    @Test
    fun speakPayloadSerializesEveryPriority() {
        Priority.values().forEach { priority ->
            val payload = buildSpeakPayload(
                SpeakMessage(text = "Test", priority = priority)
            )

            assertEquals(priority.name, payload.getString("priority"))
        }
    }

    @Test
    fun thinkPayloadUsesDocumentedActionDefaults() {
        val payload = buildThinkPayload(ThinkMessage(text = "The user clicked buy"))

        assertEquals("The user clicked buy", payload.getString("message"))
        assertTrue(payload.getBoolean("interruptable"))
        assertFalse(payload.has("priority"))
        assertEquals("interrupt", payload.getString("on_listening_action"))
        assertEquals("ignore", payload.getString("on_thinking_action"))
        assertEquals("ignore", payload.getString("on_speaking_action"))
        assertFalse(payload.has("metadata"))
    }

    @Test
    fun thinkPayloadSerializesActionsAndMetadata() {
        val payload = buildThinkPayload(
            ThinkMessage(
                text = "The user clicked buy",
                onListeningAction = ThinkListeningAction.INJECT,
                onThinkingAction = ThinkThinkingAction.APPEND,
                onSpeakingAction = ThinkSpeakingAction.IGNORE,
                interruptable = false,
                metadata = mapOf("publisher" to "user123")
            )
        )

        assertEquals("inject", payload.getString("on_listening_action"))
        assertEquals("append", payload.getString("on_thinking_action"))
        assertEquals("ignore", payload.getString("on_speaking_action"))
        assertFalse(payload.getBoolean("interruptable"))
        assertEquals("user123", payload.getJSONObject("metadata").getString("publisher"))
    }

    @Test
    fun thinkPayloadSerializesEveryListeningAction() {
        ThinkListeningAction.values().forEach { action ->
            val payload = buildThinkPayload(
                ThinkMessage(text = "Test", onListeningAction = action)
            )

            assertEquals(action.value, payload.getString("on_listening_action"))
        }
    }

    @Test
    fun thinkPayloadSerializesEveryThinkingAction() {
        ThinkThinkingAction.values().forEach { action ->
            val payload = buildThinkPayload(
                ThinkMessage(text = "Test", onThinkingAction = action)
            )

            assertEquals(action.value, payload.getString("on_thinking_action"))
        }
    }

    @Test
    fun thinkPayloadSerializesEverySpeakingAction() {
        ThinkSpeakingAction.values().forEach { action ->
            val payload = buildThinkPayload(
                ThinkMessage(text = "Test", onSpeakingAction = action)
            )

            assertEquals(action.value, payload.getString("on_speaking_action"))
        }
    }
}
