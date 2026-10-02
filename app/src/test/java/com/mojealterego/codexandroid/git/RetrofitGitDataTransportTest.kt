package com.mojealterego.codexandroid.git

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class RetrofitGitDataTransportTest {
    @Test fun mapsGitDataRequestsAndResponses() = runBlocking {
        val api = FakeGithubGitDataApi()
        val transport = RetrofitGitDataTransport(api, "token-123")

        assertEquals("head-1", transport.getRef("mojealterego/Codex-Android", "codex/feature").sha)
        assertEquals("tree-1", transport.getCommit("mojealterego/Codex-Android", "head-1").treeSha)
        assertEquals("blob-1", transport.createBlob("mojealterego/Codex-Android", "YWJj"))

        val treeSha = transport.createTree(
            "mojealterego/Codex-Android",
            "tree-1",
            listOf(GitTreeEntry("a.kt", "100644", sha = "blob-1"))
        )
        assertEquals("tree-2", treeSha)

        val commitSha = transport.createCommit(
            "mojealterego/Codex-Android",
            "Atomic",
            "tree-2",
            "head-1"
        )
        assertEquals("commit-2", commitSha)

        transport.updateRef(
            "mojealterego/Codex-Android",
            "codex/feature",
            "commit-2",
            force = false
        )

        assertEquals("Bearer token-123", api.lastAuthorization)
        assertEquals("repos/mojealterego/Codex-Android/git/ref/heads/codex/feature", api.lastRefPath)
        assertEquals("repos/mojealterego/Codex-Android/git/commits/head-1", api.lastCommitPath)
        assertEquals("base64", api.lastBlobRequest?.encoding)
        assertEquals("tree-1", api.lastTreeRequest?.baseTree)
        assertEquals(listOf("head-1"), api.lastCommitRequest?.parents)
        assertEquals(false, api.lastUpdateRefRequest?.force)
    }

    @Test fun rejectsForcePushAtTransportBoundary() = runBlocking {
        val transport = RetrofitGitDataTransport(FakeGithubGitDataApi(), "token-123")

        try {
            transport.updateRef(
                "mojealterego/Codex-Android",
                "codex/feature",
                "commit-2",
                force = true
            )
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }
}

private class FakeGithubGitDataApi : GithubGitDataApi {
    var lastAuthorization: String? = null
    var lastRefPath: String? = null
    var lastCommitPath: String? = null
    var lastBlobRequest: GitBlobRequest? = null
    var lastTreeRequest: GitTreeRequest? = null
    var lastCommitRequest: GitCommitRequest? = null
    var lastUpdateRefRequest: GitUpdateRefRequest? = null

    override suspend fun getRef(
        path: String,
        authorization: String,
        accept: String
    ): GitRefResponse {
        lastRefPath = path
        lastAuthorization = authorization
        return GitRefResponse(GitObjectResponse("head-1"))
    }

    override suspend fun getCommit(
        path: String,
        authorization: String,
        accept: String
    ): GitCommitResponse {
        lastCommitPath = path
        return GitCommitResponse("head-1", GitTreeResponse("tree-1"))
    }

    override suspend fun createBlob(
        path: String,
        authorization: String,
        accept: String,
        body: GitBlobRequest
    ): GitShaResponse {
        lastBlobRequest = body
        return GitShaResponse("blob-1")
    }

    override suspend fun createTree(
        path: String,
        authorization: String,
        accept: String,
        body: GitTreeRequest
    ): GitShaResponse {
        lastTreeRequest = body
        return GitShaResponse("tree-2")
    }

    override suspend fun createCommit(
        path: String,
        authorization: String,
        accept: String,
        body: GitCommitRequest
    ): GitShaResponse {
        lastCommitRequest = body
        return GitShaResponse("commit-2")
    }

    override suspend fun updateRef(
        path: String,
        authorization: String,
        accept: String,
        body: GitUpdateRefRequest
    ): GitRefResponse {
        lastUpdateRefRequest = body
        return GitRefResponse(GitObjectResponse(body.sha))
    }
}
