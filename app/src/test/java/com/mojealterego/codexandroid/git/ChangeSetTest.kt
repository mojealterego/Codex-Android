package com.mojealterego.codexandroid.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFailsWith
import org.junit.Test

class ChangeSetTest {
    @Test fun acceptsMultipleUniqueFilesOnCodexBranch() {
        val draft = ChangeSetDraft(
            targetBranch = "codex/multi-file",
            expectedHeadSha = "head123",
            commitMessage = "Update two files",
            files = listOf(
                FileDraft("a.kt", FileOperation.UPSERT, "blob-a", "100644", "new-a"),
                FileDraft("b.kt", FileOperation.UPSERT, "blob-b", "100644", "new-b")
            )
        )

        assertEquals(2, draft.validated().files.size)
    }

    @Test fun rejectsDuplicatePaths() {
        val draft = ChangeSetDraft(
            targetBranch = "codex/dup",
            expectedHeadSha = "head123",
            commitMessage = "Duplicate",
            files = listOf(
                FileDraft("a.kt", FileOperation.UPSERT, "one", "100644", "x"),
                FileDraft("a.kt", FileOperation.UPSERT, "two", "100644", "y")
            )
        )

        assertIllegalArgument { draft.validated() }
    }

    @Test fun rejectsNonCodexBranch() {
        val draft = ChangeSetDraft(
            targetBranch = "main",
            expectedHeadSha = "head123",
            commitMessage = "No direct main writes",
            files = listOf(FileDraft("a.kt", FileOperation.UPSERT, "one", "100644", "x"))
        )

        assertIllegalArgument { draft.validated() }
    }


    private fun assertIllegalArgument(block: () -> Unit) {
        try {
            block()
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }
}
