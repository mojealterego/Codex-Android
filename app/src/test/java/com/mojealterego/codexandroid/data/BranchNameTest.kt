package com.mojealterego.codexandroid.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BranchNameTest {
    @Test fun normalizesAgentBranchName() {
        assertEquals("codex/fix-auth-flow", codexBranchName(" Fix Auth Flow "))
    }

    @Test fun rejectsEmptyAgentBranchName() {
        assertFalse(isValidCodexBranchName("codex/"))
        assertTrue(isValidCodexBranchName("codex/fix-auth"))
    }
}
