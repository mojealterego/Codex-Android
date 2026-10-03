package com.mojealterego.codexandroid.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistedAgentSessionTest {
    @Test fun roundTripsAgentSessionWithoutCredentials() {
        val response = AgentSessionResponse(
            sessionId = "sess_123",
            state = "in_progress",
            environmentId = "env_123",
            repository = "owner/repo",
            baseBranch = "codex/recovery",
            baseSha = "abc123",
            eventsPath = "/v1/agents/sessions/sess_123/events"
        )

        val persisted = response.toPersistedAgentSession()
        val restored = persisted.toAgentSessionResponse()

        assertEquals(response, restored)
        assertTrue(persisted.matches("owner/repo", "codex/recovery"))
        assertFalse(persisted.matches("owner/repo", "main"))
        assertFalse(persisted.matches("other/repo", "codex/recovery"))
    }
}
