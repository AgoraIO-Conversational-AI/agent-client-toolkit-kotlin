package io.agora.agent.toolkit.sample.ui

import io.agora.conversational.api.AgentManualEosPayload
import io.agora.conversational.api.UserManualEventPayload
import java.util.Locale

object ManualTurnDemoUi {
    enum class Action(val label: String) {
        SOS("SOS"),
        EOS("EOS"),
    }

    fun formatPublishLog(action: Action, requestId: String): String {
        return "Manual ${action.label} publish requestId=$requestId"
    }

    fun formatPublishFailureLog(action: Action, requestId: String, errorMessage: String): String {
        return "Manual ${action.label} publish failed requestId=$requestId error=$errorMessage"
    }

    fun formatUserResultLog(action: Action, payload: UserManualEventPayload): String {
        val status = if (payload.success) "success" else "failed"
        val error = payload.errorMessage?.let { " error=$it" }.orEmpty()
        return String.format(
            Locale.US,
            "Manual %s result %s requestId=%s turnId=%s%s",
            action.label,
            status,
            payload.requestId,
            payload.turnId,
            error
        )
    }

    fun formatAgentEosLog(payload: AgentManualEosPayload): String {
        return String.format(
            Locale.US,
            "Agent manual EOS reason=%s turnId=%d maxDurationMs=%d",
            payload.reason,
            payload.turnId,
            payload.maxDurationMs
        )
    }
}
