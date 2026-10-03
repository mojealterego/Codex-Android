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

    @Test fun exposesExpectedWorkspaceSections() {
        assertTrue(WorkspaceSection.entries.containsAll(
            listOf(
                WorkspaceSection.FILES,
                WorkspaceSection.COMMITS,
                WorkspaceSection.CI,
                WorkspaceSection.PULL_REQUEST
            )
        ))
    }
}
