package com.mojealterego.codexandroid.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRuntimeDiagnosticsPolicyTest {
    @Test fun runtimeIsReadyWhenBffAndAgentsApiAreHealthy() {
        val diagnostics = AgentRuntimeDiagnostics(
            status = "ok",
            storageBackend = "sqlite",
            persistentStorage = false,
            agentsApi = "configured",
            githubPrivateAccess = false
        )

        assertTrue(diagnostics.isReadyForAgent())
    }

    @Test fun runtimeIsNotReadyWhenAgentsApiIsNotConfigured() {
        val diagnostics = AgentRuntimeDiagnostics(
            status = "ok",
            storageBackend = "postgres",
            persistentStorage = true,
            agentsApi = "unknown",
            githubPrivateAccess = true
        )

        assertFalse(diagnostics.isReadyForAgent())
    }

    @Test fun runtimeIsNotReadyWhenBffReportsNonOkStatus() {
        val diagnostics = AgentRuntimeDiagnostics(
            status = "degraded",
            storageBackend = "postgres",
            persistentStorage = true,
            agentsApi = "configured",
            githubPrivateAccess = true
        )

        assertFalse(diagnostics.isReadyForAgent())
    }
}
