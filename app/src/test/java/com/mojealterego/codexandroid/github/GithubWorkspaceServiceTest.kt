package com.mojealterego.codexandroid.github

import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GithubWorkspaceServiceTest {
    @Test fun loadsHistoryRunsJobsLogsAndArtifactsForSelectedBranch() = runBlocking {
        val api = FakeGithubWorkspaceApi()
        val service = GithubWorkspaceService(api, "token-123")
        val repo = "mojealterego/Codex-Android"
        val branch = "codex/actions-p1"

        val commits = service.commits(repo, branch)
        val runs = service.workflowRuns(repo, branch)
        val jobs = service.workflowJobs(repo, 101)
        val log = service.jobLog(repo, 202)
        val artifacts = service.workflowArtifacts(repo, 101)

        assertEquals("c1", commits.single().sha)
        assertEquals(CiState.SUCCESS, runs.single().ciState)
        assertEquals("build", jobs.single().name)
        assertEquals("line-1\nline-2", log)
        assertEquals("Codex-Android-debug", artifacts.single().name)

        assertEquals(branch, api.lastCommitBranch)
        assertEquals(branch, api.lastRunBranch)
        assertEquals("Bearer token-123", api.lastAuthorization)
        assertEquals(workflowJobsPath(repo, 101), api.lastJobsPath)
        assertEquals(workflowJobLogsPath(repo, 202), api.lastLogPath)
        assertEquals(workflowArtifactsPath(repo, 101), api.lastArtifactsPath)
    }

    @Test fun createsPullRequestFromCodexBranchToBaseBranch() = runBlocking {
        val api = FakeGithubWorkspaceApi()
        val service = GithubWorkspaceService(api, "token-123")

        val pr = service.createPullRequest(
            repoFullName = "mojealterego/Codex-Android",
            head = "codex/actions-p1",
            base = "main",
            title = "Add Actions UI",
            body = "Implements CI workflow view."
        )

        assertEquals(7L, pr.number)
        val request = requireNotNull(api.lastPullRequest)
        assertEquals("codex/actions-p1", request.head)
        assertEquals("main", request.base)
        assertEquals("Add Actions UI", request.title)
        assertTrue(request.maintainerCanModify)
    }
}

private class FakeGithubWorkspaceApi : GithubWorkspaceApi {
    var lastAuthorization: String? = null
    var lastCommitBranch: String? = null
    var lastRunBranch: String? = null
    var lastJobsPath: String? = null
    var lastLogPath: String? = null
    var lastArtifactsPath: String? = null
    var lastPullRequest: CreatePullRequestRequest? = null

    override suspend fun commits(
        path: String,
        authorization: String,
        accept: String,
        branch: String,
        perPage: Int
    ): List<GithubCommitItem> {
        lastAuthorization = authorization
        lastCommitBranch = branch
        return listOf(
            GithubCommitItem(
                sha = "c1",
                commit = GithubCommitDetails("Commit one", GithubCommitAuthor("A", "2026-10-02T18:00:00Z"))
            )
        )
    }

    override suspend fun createPullRequest(
        path: String,
        authorization: String,
        accept: String,
        body: CreatePullRequestRequest
    ): GithubPullRequestResponse {
        lastPullRequest = body
        return GithubPullRequestResponse(7, "open", body.title, "https://example/pr/7")
    }

    override suspend fun workflowRuns(
        path: String,
        authorization: String,
        accept: String,
        branch: String,
        perPage: Int
    ): WorkflowRunsResponse {
        lastRunBranch = branch
        return WorkflowRunsResponse(
            listOf(GithubWorkflowRun(101, "Android CI", "completed", "success", branch, "head", null))
        )
    }

    override suspend fun workflowJobs(
        path: String,
        authorization: String,
        accept: String
    ): WorkflowJobsResponse {
        lastJobsPath = path
        return WorkflowJobsResponse(listOf(GithubWorkflowJob(202, "build", "completed", "success", null)))
    }

    override suspend fun jobLogs(
        path: String,
        authorization: String,
        accept: String
    ): okhttp3.ResponseBody {
        lastLogPath = path
        return "line-1\nline-2".toResponseBody()
    }

    override suspend fun artifacts(
        path: String,
        authorization: String,
        accept: String
    ): WorkflowArtifactsResponse {
        lastArtifactsPath = path
        return WorkflowArtifactsResponse(
            listOf(
                GithubArtifact(
                    id = 303,
                    name = "Codex-Android-debug",
                    sizeInBytes = 1234,
                    expired = false,
                    digest = "sha256:abc",
                    expiresAt = "2026-10-16T00:00:00Z"
                )
            )
        )
    }
}
