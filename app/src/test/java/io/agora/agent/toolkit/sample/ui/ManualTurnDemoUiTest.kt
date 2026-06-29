package io.agora.agent.toolkit.sample.ui

import io.agora.agent.toolkit.sample.api.TurnDetectionMode
import io.agora.conversational.api.AgentManualEosPayload
import io.agora.conversational.api.UserManualEventPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualTurnDemoUiTest {
    @Test
    fun uiStateExposesEnabledManualActionsFromIndependentModes() {
        val defaultState = AgentChatViewModel.ConversationUiState()
        assertFalse(defaultState.isManualSosEnabled)
        assertFalse(defaultState.isManualEosEnabled)
        assertFalse(defaultState.isManualTurnDetectionEnabled)

        val manualEosState = AgentChatViewModel.ConversationUiState(
            eosDetectionMode = TurnDetectionMode.MANUAL
        )
        assertFalse(manualEosState.isManualSosEnabled)
        assertTrue(manualEosState.isManualEosEnabled)
        assertTrue(manualEosState.isManualTurnDetectionEnabled)

        val manualSosEosState = AgentChatViewModel.ConversationUiState(
            sosDetectionMode = TurnDetectionMode.MANUAL,
            eosDetectionMode = TurnDetectionMode.MANUAL
        )
        assertTrue(manualSosEosState.isManualSosEnabled)
        assertTrue(manualSosEosState.isManualEosEnabled)
        assertTrue(manualSosEosState.isManualTurnDetectionEnabled)
    }

    @Test
    fun uiStateAllowsTurnDetectionModeChangesOnlyBeforeStartup() {
        assertTrue(
            AgentChatViewModel.ConversationUiState(
                connectionState = AgentChatViewModel.ConnectionState.Idle
            ).canChangeTurnDetectionMode
        )
        assertFalse(
            AgentChatViewModel.ConversationUiState(
                connectionState = AgentChatViewModel.ConnectionState.Connecting
            ).canChangeTurnDetectionMode
        )
        assertFalse(
            AgentChatViewModel.ConversationUiState(
                connectionState = AgentChatViewModel.ConnectionState.Connected
            ).canChangeTurnDetectionMode
        )
    }

    @Test
    fun userResultLogIncludesRequestTurnAndErrorDetails() {
        val log = ManualTurnDemoUi.formatUserResultLog(
            action = ManualTurnDemoUi.Action.EOS,
            payload = UserManualEventPayload(
                success = false,
                requestId = "eos-req-1710000005200",
                turnId = null,
                errorMessage = "No turns available for EOS labeling."
            )
        )

        assertEquals(
            "Manual EOS result failed requestId=eos-req-1710000005200 turnId=null error=No turns available for EOS labeling.",
            log
        )
    }

    @Test
    fun publishFailureLogIncludesRequestId() {
        val log = ManualTurnDemoUi.formatPublishFailureLog(
            action = ManualTurnDemoUi.Action.SOS,
            requestId = "sos-req-1710000000100-ab12cd34",
            errorMessage = "RTM publish failed"
        )

        assertEquals(
            "Manual SOS publish failed requestId=sos-req-1710000000100-ab12cd34 error=RTM publish failed",
            log
        )
    }

    @Test
    fun agentManualEosLogIncludesReasonTurnAndLimit() {
        val log = ManualTurnDemoUi.formatAgentEosLog(
            AgentManualEosPayload(
                reason = "max_audio_duration",
                maxDurationMs = 600000L,
                turnId = 23L
            )
        )

        assertEquals(
            "Agent manual EOS reason=max_audio_duration turnId=23 maxDurationMs=600000",
            log
        )
    }
}
