package io.agora.conversational.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualTurnTest {
    @Test
    fun publicApiUsesManualSosAndManualEosMethods() {
        val methods = IConversationalAIAPI::class.java.methods
        val methodNames = methods.map { it.name }

        assertTrue(methodNames.contains("manualSOS"))
        assertTrue(methodNames.contains("manualEOS"))
        assertFalse(methodNames.contains("userManualTurn"))

        val manualSos = methods.first { it.name == "manualSOS" }
        val manualEos = methods.first { it.name == "manualEOS" }
        assertEquals(2, manualSos.parameterTypes.size)
        assertEquals(String::class.java, manualSos.parameterTypes[0])
        assertEquals(2, manualEos.parameterTypes.size)
        assertEquals(String::class.java, manualEos.parameterTypes[0])
    }

    @Test
    fun parseUserManualSosResultEvent() {
        val message = mapOf(
            "event_type" to "user.manual_sos.result",
            "event_id" to "evt-sos-001",
            "event_ms" to 1773901235435L,
            "payload" to mapOf(
                "success" to true,
                "request_id" to "sos-req-20260612-001",
                "turn_id" to 42L
            )
        )
        val parsed = parseManualTurnEvent(resolveMessageType(message), message)

        val event = parsed as ParsedManualTurnEvent.UserManualSos
        assertEquals("evt-sos-001", event.event.eventId)
        assertEquals(1773901235435L, event.event.timestamp)
        assertTrue(event.event.payload.success)
        assertEquals("sos-req-20260612-001", event.event.payload.requestId)
        assertEquals(42L, event.event.payload.turnId)
        assertNull(event.event.payload.errorMessage)
    }

    @Test
    fun parseUserManualSosResultEventFromObjectField() {
        val message = mapOf(
            "object" to "user.manual_sos.result",
            "event_id" to "evt-sos-002",
            "event_ms" to 1773901235438L,
            "payload" to mapOf(
                "success" to true,
                "request_id" to "sos-req-20260612-002",
                "turn_id" to 44L
            )
        )
        val parsed = parseManualTurnEvent(resolveMessageType(message), message)

        val event = parsed as ParsedManualTurnEvent.UserManualSos
        assertEquals("evt-sos-002", event.event.eventId)
        assertEquals(1773901235438L, event.event.timestamp)
        assertTrue(event.event.payload.success)
        assertEquals("sos-req-20260612-002", event.event.payload.requestId)
        assertEquals(44L, event.event.payload.turnId)
        assertNull(event.event.payload.errorMessage)
    }

    @Test
    fun parseUserManualEosResultEventWithFailureAndNoTurnId() {
        val message = mapOf(
            "event_type" to "user.manual_eos.result",
            "event_id" to "evt-eos-001",
            "event_ms" to 1773901235436L,
            "payload" to mapOf(
                "success" to false,
                "request_id" to "eos-req-20260612-001",
                "error_message" to "No turns available for EOS labeling."
            )
        )
        val parsed = parseManualTurnEvent(resolveMessageType(message), message)

        val event = parsed as ParsedManualTurnEvent.UserManualEos
        assertEquals("evt-eos-001", event.event.eventId)
        assertEquals(1773901235436L, event.event.timestamp)
        assertFalse(event.event.payload.success)
        assertEquals("eos-req-20260612-001", event.event.payload.requestId)
        assertNull(event.event.payload.turnId)
        assertEquals("No turns available for EOS labeling.", event.event.payload.errorMessage)
    }

    @Test
    fun parseAgentManualEosResultEvent() {
        val message = mapOf(
            "event_type" to "assistant.manual_eos.result",
            "event_id" to "evt-agent-eos-001",
            "event_ms" to 1773901235437L,
            "payload" to mapOf(
                "reason" to "max_duration",
                "max_duration_ms" to 60000L,
                "turn_id" to 43L
            )
        )
        val parsed = parseManualTurnEvent(resolveMessageType(message), message)

        val event = parsed as ParsedManualTurnEvent.AgentManualEos
        assertEquals("evt-agent-eos-001", event.event.eventId)
        assertEquals(1773901235437L, event.event.timestamp)
        assertEquals("max_duration", event.event.payload.reason)
        assertEquals(60000L, event.event.payload.maxDurationMs)
        assertEquals(43L, event.event.payload.turnId)
    }
}
