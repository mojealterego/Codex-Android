package com.mojealterego.codexandroid.github

interface WorkspaceGateway {
    suspend fun commits(repoFullName: String, branch: String): List<GithubCommitItem>

    suspend fun createPullRequest(
        repoFullName: String,
        head: String,
        base: String,
        title: String,
        body: String?
    ): GithubPullRequestResponse

    suspend fun workflowRuns(
        repoFullName: String,
        branch: String
    ): List<GithubWorkflowRun>

    suspend fun workflowJobs(
        repoFullName: String,
        runId: Long
    ): List<GithubWorkflowJob>

    suspend fun jobLog(
        repoFullName: String,
        jobId: Long
    ): String

    suspend fun workflowArtifacts(
        repoFullName: String,
        runId: Long
    ): List<GithubArtifact>
}

data class RunDetails(
    val jobs: List<GithubWorkflowJob>,
    val artifacts: List<GithubArtifact>
)

class WorkspaceController(
    private val gateway: WorkspaceGateway
) {
    suspend fun loadCommits(
        repoFullName: String,
        branch: String
    ): List<GithubCommitItem> =
        gateway.commits(repoFullName, branch)

    suspend fun loadRuns(
        repoFullName: String,
        branch: String
    ): List<GithubWorkflowRun> =
        gateway.workflowRuns(repoFullName, branch)

    suspend fun loadRunDetails(
        repoFullName: String,
        runId: Long
    ): RunDetails =
        RunDetails(
            jobs = gateway.workflowJobs(repoFullName, runId),
            artifacts = gateway.workflowArtifacts(repoFullName, runId)
        )

    suspend fun loadJobLog(
        repoFullName: String,
        jobId: Long
    ): String =
        gateway.jobLog(repoFullName, jobId)

    suspend fun createPullRequest(
        repoFullName: String,
        head: String,
        base: String,
        title: String,
        body: String?
    ): GithubPullRequestResponse {
        require(canCreatePullRequest(head, base, title)) {
            "Pull request requires codex/* head, different base branch and non-blank title"
        }
        return gateway.createPullRequest(repoFullName, head, base, title, body)
    }
}
