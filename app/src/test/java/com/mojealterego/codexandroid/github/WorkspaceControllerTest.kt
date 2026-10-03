package com.mojealterego.codexandroid.github

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceControllerTest {
    @Test fun loadsCommitsCiJobsLogsArtifactsAndCreatesPr() = runBlocking {
        val gateway = FakeWorkspaceGateway()
        val controller = WorkspaceController(gateway)

        val commits = controller.loadCommits("owner/repo", "codex/ui")
        val runs = controller.loadRuns("owner/repo", "codex/ui")
        val details = controller.loadRunDetails("owner/repo", 11)
        val log = controller.loadJobLog("owner/repo", 21)
        val pr = controller.createPullRequest(
            repoFullName = "owner/repo",
            head = "codex/ui",
            base = "main",
            title = "Add mobile workspace",
            body = "UI wiring"
        )

        assertEquals("c1", commits.single().sha)
        assertEquals(11L, runs.single().id)
        assertEquals("build", details.jobs.single().name)
        assertEquals("Codex-Android-debug", details.artifacts.single().name)
        assertEquals("hello log", log)
        assertEquals(5L, pr.number)
        assertTrue(gateway.createPrCalled)
    }
}

private class FakeWorkspaceGateway : WorkspaceGateway {
    var createPrCalled = false

    override suspend fun commits(repoFullName: String, branch: String) =
        listOf(GithubCommitItem("c1", GithubCommitDetails("msg", null)))

    override suspend fun createPullRequest(
        repoFullName: String,
        head: String,
        base: String,
        title: String,
        body: String?
    ): GithubPullRequestResponse {
        createPrCalled = true
        return GithubPullRequestResponse(5, "open", title, "https://example/pr/5")
    }

    override suspend fun workflowRuns(repoFullName: String, branch: String) =
        listOf(GithubWorkflowRun(11, "Android CI", "completed", "success", branch, "head", null))

    override suspend fun workflowJobs(repoFullName: String, runId: Long) =
        listOf(GithubWorkflowJob(21, "build", "completed", "success", null))

    override suspend fun jobLog(repoFullName: String, jobId: Long) = "hello log"

    override suspend fun workflowArtifacts(repoFullName: String, runId: Long) =
        listOf(GithubArtifact(31, "Codex-Android-debug", 100, false, "sha256:abc", null))
}
