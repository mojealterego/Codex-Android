package com.mojealterego.codexandroid.agent

import com.mojealterego.codexandroid.git.FileOperation
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class AgentChangeSetMapperTest {
    @Test fun mapsValidatedAgentChangesToLocalChangeSetDraft() {
        val response = AgentChangeSetResponse(
            sessionId = "sess_1",
            turnId = "turn_1",
            baseBranch = "codex/review",
            baseSha = "head123",
            files = listOf(
                AgentFileChangeResponse(
                    path = "a.kt",
                    operation = "upsert",
                    mode = "100644",
                    content = "new",
                    diff = "@@ diff",
                    renameFrom = null
                ),
                AgentFileChangeResponse(
                    path = "old.txt",
                    operation = "delete",
                    mode = "100644",
                    content = null,
                    diff = "@@ delete",
                    renameFrom = null
                )
            )
        )

        val draft = response.toChangeSetDraft(
            targetBranch = "codex/review",
            currentHeadSha = "head123",
            commitMessage = "Apply agent changes"
        )

        assertEquals("head123", draft.expectedHeadSha)
        assertEquals("codex/review", draft.targetBranch)
        assertEquals(2, draft.files.size)
        assertEquals(FileOperation.UPSERT, draft.files[0].operation)
        assertEquals(FileOperation.DELETE, draft.files[1].operation)
    }

    @Test fun rejectsStaleOrDifferentBranchBeforePublishing() {
        val response = AgentChangeSetResponse(
            sessionId = "sess_1",
            turnId = "turn_1",
            baseBranch = "codex/review",
            baseSha = "old-head",
            files = listOf(
                AgentFileChangeResponse(
                    path = "a.kt",
                    operation = "upsert",
                    mode = "100644",
                    content = "new",
                    diff = "@@ diff",
                    renameFrom = null
                )
            )
        )

        assertIllegalArgument {
            response.toChangeSetDraft(
                targetBranch = "codex/other",
                currentHeadSha = "old-head",
                commitMessage = "Apply"
            )
        }
        assertIllegalArgument {
            response.toChangeSetDraft(
                targetBranch = "codex/review",
                currentHeadSha = "new-head",
                commitMessage = "Apply"
            )
        }
    }

    private fun assertIllegalArgument(block: () -> Unit) {
        try {
            block()
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }
}
