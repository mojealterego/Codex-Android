package com.mojealterego.codexandroid.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentEventSemanticsTest {
    @Test fun rootTurnTerminalEventsEndActiveTurn() {
        assertTrue(
            isRootTurnTerminal(
                AgentStreamEvent(
                    "agent.session.turn.completed",
                    "{\"turn\":{\"subagent_id\":null}}"
                )
            )
        )
        assertTrue(
            isRootTurnTerminal(
                AgentStreamEvent(
                    "agent.session.turn.failed",
                    "{\"turn\":{\"subagent_id\":null}}"
                )
            )
        )
        assertTrue(
            isRootTurnTerminal(
                AgentStreamEvent(
                    "agent.session.turn.cancelled",
                    "{\"turn\":{\"subagent_id\":null}}"
                )
            )
        )
    }

    @Test fun subagentAndIdleEventsDoNotEndRootTurn() {
        assertFalse(
            isRootTurnTerminal(
                AgentStreamEvent(
                    "agent.session.turn.completed",
                    "{\"turn\":{\"subagent_id\":\"sub_1\"}}"
                )
            )
        )
        assertFalse(
            isRootTurnTerminal(
                AgentStreamEvent(
                    "agent.session.idle",
                    "{\"type\":\"agent.session.idle\"}"
                )
            )
        )
    }

    @Test fun lifecycleFailureEndsActiveTurn() {
        assertTrue(
            isRootTurnTerminal(
                AgentStreamEvent(
                    "agent.session.failed",
                    "{\"type\":\"agent.session.failed\"}"
                )
            )
        )
        assertTrue(
            isRootTurnTerminal(
                AgentStreamEvent(
                    "agent.session.environment.failed",
                    "{\"type\":\"agent.session.environment.failed\"}"
                )
            )
        )
    }
}
