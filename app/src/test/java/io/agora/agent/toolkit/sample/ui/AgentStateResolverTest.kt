package io.agora.agent.toolkit.sample.ui

import io.agora.conversational.api.AgentState
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentStateResolverTest {
    @Test
    fun `independent activity uses speaking thinking listening priority`() {
        assertEquals(AgentState.SPEAKING, resolveAgentState(true, true, true))
        assertEquals(AgentState.THINKING, resolveAgentState(true, true, false))
        assertEquals(AgentState.LISTENING, resolveAgentState(true, false, false))
    }

    @Test
    fun `no independent activity resolves to silent`() {
        assertEquals(AgentState.SILENT, resolveAgentState(false, false, false))
    }
}
