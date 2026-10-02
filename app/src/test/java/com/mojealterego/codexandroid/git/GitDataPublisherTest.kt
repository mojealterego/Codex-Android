package com.mojealterego.codexandroid.git

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test

class GitDataPublisherTest {
    @Test fun publishesMultipleFilesAsOneCommitWithoutForce() = runBlocking {
        val transport = FakeGitDataTransport()
        val publisher = GitDataPublisher(transport)
        val draft = ChangeSetDraft(
            targetBranch = "codex/multi-file",
            expectedHeadSha = "head-1",
            commitMessage = "Atomic update",
            files = listOf(
                FileDraft("a.kt", FileOperation.UPSERT, "old-a", "100644", "new-a"),
                FileDraft("b.kt", FileOperation.UPSERT, "old-b", "100644", "new-b")
            )
        )

        val result = publisher.publish("mojealterego/Codex-Android", draft)

        assertEquals("commit-2", result.commitSha)
        assertEquals(1, transport.createdCommits)
        assertEquals(2, transport.lastTreeEntries.size)
        assertEquals(1, transport.updateRefCalls)
        assertFalse(transport.lastForce ?: true)
    }

    @Test fun rejectsMovedHeadBeforeWriting() = runBlocking {
        val transport = FakeGitDataTransport(currentHead = "remote-head")
        val publisher = GitDataPublisher(transport)
        val draft = ChangeSetDraft(
            targetBranch = "codex/conflict",
            expectedHeadSha = "head-1",
            commitMessage = "Conflict",
            files = listOf(FileDraft("a.kt", FileOperation.UPSERT, "old-a", "100644", "new-a"))
        )

        assertHeadMoved { publisher.publish("mojealterego/Codex-Android", draft) }

        assertEquals(0, transport.createdCommits)
        assertEquals(0, transport.updateRefCalls)
    }

    @Test fun rejectsMovedHeadBeforeRefUpdate() = runBlocking {
        val transport = FakeGitDataTransport(moveHeadAfterCommit = true)
        val publisher = GitDataPublisher(transport)
        val draft = ChangeSetDraft(
            targetBranch = "codex/race",
            expectedHeadSha = "head-1",
            commitMessage = "Race",
            files = listOf(FileDraft("a.kt", FileOperation.UPSERT, "old-a", "100644", "new-a"))
        )

        assertHeadMoved { publisher.publish("mojealterego/Codex-Android", draft) }

        assertEquals(1, transport.createdCommits)
        assertEquals(0, transport.updateRefCalls)
    }

    private suspend fun assertHeadMoved(block: suspend () -> Unit) {
        try {
            block()
            fail("Expected HeadMovedException")
        } catch (_: HeadMovedException) {
        }
    }
}

private class FakeGitDataTransport(
    private var currentHead: String = "head-1",
    private val moveHeadAfterCommit: Boolean = false
) : GitDataTransport {
    var createdCommits: Int = 0
    var updateRefCalls: Int = 0
    var lastForce: Boolean? = null
    var lastTreeEntries: List<GitTreeEntry> = emptyList()
    private var blobIndex = 0

    override suspend fun getRef(repoFullName: String, branch: String): GitRef =
        GitRef(currentHead)

    override suspend fun getCommit(repoFullName: String, sha: String): GitCommit =
        GitCommit(sha = sha, treeSha = "tree-1")

    override suspend fun createBlob(repoFullName: String, contentBase64: String): String {
        blobIndex += 1
        return "blob-" + blobIndex
    }

    override suspend fun createTree(
        repoFullName: String,
        baseTreeSha: String,
        entries: List<GitTreeEntry>
    ): String {
        lastTreeEntries = entries
        return "tree-2"
    }

    override suspend fun createCommit(
        repoFullName: String,
        message: String,
        treeSha: String,
        parentSha: String
    ): String {
        createdCommits += 1
        if (moveHeadAfterCommit) currentHead = "remote-after-commit"
        return "commit-2"
    }

    override suspend fun updateRef(
        repoFullName: String,
        branch: String,
        newSha: String,
        force: Boolean
    ) {
        updateRefCalls += 1
        lastForce = force
        currentHead = newSha
    }
}
