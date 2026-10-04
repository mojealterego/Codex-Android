package com.mojealterego.codexandroid.github

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceNavigationTest {
    @Test fun pullRequestRequiresCodexBranchDifferentFromBaseAndTitle() {
        assertTrue(canCreatePullRequest("codex/actions-p1", "main", "Add Actions UI"))
        assertFalse(canCreatePullRequest("main", "main", "Direct main"))
        assertFalse(canCreatePullRequest("feature/test", "main", "Wrong prefix"))
        assertFalse(canCreatePullRequest("codex/actions-p1", "main", "   "))
    }

    @Test fun agentRequiresCodexBranchPinnedHeadBackendAndTask() {
        assertTrue(
            canStartAgent(
                branch = "codex/review",
                headSha = "abc123",
                backendUrl = "https://bff.example.com",
                task = "Update tests",
                runtimeReady = true
            )
        )
        assertFalse(canStartAgent("main", "abc123", "https://bff.example.com", "Task", true))
        assertFalse(canStartAgent("codex/review", "", "https://bff.example.com", "Task", true))
        assertFalse(canStartAgent("codex/review", "abc123", "", "Task", true))
        assertFalse(canStartAgent("codex/review", "abc123", "https://bff.example.com", "  ", true))
        assertFalse(canStartAgent("codex/review", "abc123", "https://bff.example.com", "Task", false))
    }

    @Test fun exposesExpectedWorkspaceSections() {
        assertTrue(WorkspaceSection.entries.containsAll(
            listOf(
                WorkspaceSection.FILES,
                WorkspaceSection.COMMITS,
                WorkspaceSection.AGENT,
                WorkspaceSection.CI,
                WorkspaceSection.PULL_REQUEST
            )
        ))
    }
}
