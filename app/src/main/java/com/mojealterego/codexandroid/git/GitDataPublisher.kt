package com.mojealterego.codexandroid.git

import java.util.Base64

data class GitRef(val sha: String)

data class GitCommit(
    val sha: String,
    val treeSha: String
)

data class GitTreeEntry(
    val path: String,
    val mode: String,
    val type: String = "blob",
    val sha: String?
)

data class PublishResult(
    val previousHeadSha: String,
    val commitSha: String,
    val treeSha: String,
    val filesChanged: Int
)

class HeadMovedException(
    val expectedSha: String,
    val actualSha: String
) : IllegalStateException(
    "Branch HEAD moved: expected $expectedSha but was $actualSha"
)

interface GitDataTransport {
    suspend fun getRef(repoFullName: String, branch: String): GitRef
    suspend fun getCommit(repoFullName: String, sha: String): GitCommit
    suspend fun createBlob(repoFullName: String, contentBase64: String): String
    suspend fun createTree(
        repoFullName: String,
        baseTreeSha: String,
        entries: List<GitTreeEntry>
    ): String
    suspend fun createCommit(
        repoFullName: String,
        message: String,
        treeSha: String,
        parentSha: String
    ): String
    suspend fun updateRef(
        repoFullName: String,
        branch: String,
        newSha: String,
        force: Boolean
    )
}

class GitDataPublisher(
    private val transport: GitDataTransport
) {
    suspend fun publish(
        repoFullName: String,
        draft: ChangeSetDraft
    ): PublishResult {
        val changeSet = draft.validated()

        val initialRef = transport.getRef(repoFullName, changeSet.targetBranch)
        if (initialRef.sha != changeSet.expectedHeadSha) {
            throw HeadMovedException(changeSet.expectedHeadSha, initialRef.sha)
        }

        val baseCommit = transport.getCommit(repoFullName, initialRef.sha)
        val treeEntries = mutableListOf<GitTreeEntry>()

        for (file in changeSet.files) {
            when (file.operation) {
                FileOperation.UPSERT -> {
                    val blobSha = transport.createBlob(
                        repoFullName,
                        file.draftContent.orEmpty().toBase64()
                    )
                    treeEntries += GitTreeEntry(
                        path = file.path,
                        mode = file.mode,
                        sha = blobSha
                    )
                }

                FileOperation.DELETE -> {
                    treeEntries += GitTreeEntry(
                        path = file.path,
                        mode = file.mode,
                        sha = null
                    )
                }

                FileOperation.RENAME -> {
                    treeEntries += GitTreeEntry(
                        path = requireNotNull(file.renameFrom),
                        mode = file.mode,
                        sha = null
                    )
                    val blobSha = transport.createBlob(
                        repoFullName,
                        file.draftContent.orEmpty().toBase64()
                    )
                    treeEntries += GitTreeEntry(
                        path = file.path,
                        mode = file.mode,
                        sha = blobSha
                    )
                }
            }
        }

        val newTreeSha = transport.createTree(
            repoFullName = repoFullName,
            baseTreeSha = baseCommit.treeSha,
            entries = treeEntries
        )

        val newCommitSha = transport.createCommit(
            repoFullName = repoFullName,
            message = changeSet.commitMessage,
            treeSha = newTreeSha,
            parentSha = initialRef.sha
        )

        val finalRefCheck = transport.getRef(repoFullName, changeSet.targetBranch)
        if (finalRefCheck.sha != changeSet.expectedHeadSha) {
            throw HeadMovedException(changeSet.expectedHeadSha, finalRefCheck.sha)
        }

        transport.updateRef(
            repoFullName = repoFullName,
            branch = changeSet.targetBranch,
            newSha = newCommitSha,
            force = false
        )

        return PublishResult(
            previousHeadSha = initialRef.sha,
            commitSha = newCommitSha,
            treeSha = newTreeSha,
            filesChanged = changeSet.files.size
        )
    }
}

private fun String.toBase64(): String =
    Base64.getEncoder().encodeToString(toByteArray(Charsets.UTF_8))
