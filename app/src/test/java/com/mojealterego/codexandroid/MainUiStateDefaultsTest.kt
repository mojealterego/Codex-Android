package com.mojealterego.codexandroid

import org.junit.Assert.assertEquals
import org.junit.Test

class MainUiStateDefaultsTest {
    @Test fun defaultsAgentModelToVerifiedAgentsApiModel() {
        assertEquals("gpt-5.6-sol", MainUiState().agentModel)
    }
}
