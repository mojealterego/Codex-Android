package com.mojealterego.codexandroid.github

import com.google.gson.annotations.SerializedName
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query
import retrofit2.http.Streaming
import retrofit2.http.Url

interface GithubWorkspaceApi {
    @GET
    override suspend fun commits(
        @Url path: String,
        @Header("Authorization") authorization: String,
        @Header("Accept") accept: String = GITHUB_ACCEPT,
        @Query("sha") branch: String,
        @Query("per_page") perPage: Int = 30
    ): List<GithubCommitItem>

    @POST
    override suspend fun createPullRequest(
        @Url path: String,
        @Header("Authorization") authorization: String,
        @Header("Accept") accept: String = GITHUB_ACCEPT,
        @Body body: CreatePullRequestRequest
    ): GithubPullRequestResponse

    @GET
    override suspend fun workflowRuns(
        @Url path: String,
        @Header("Authorization") authorization: String,
        @Header("Accept") accept: String = GITHUB_ACCEPT,
        @Query("branch") branch: String,
        @Query("per_page") perPage: Int = 20
    ): WorkflowRunsResponse

    @GET
    override suspend fun workflowJobs(
        @Url path: String,
        @Header("Authorization") authorization: String,
        @Header("Accept") accept: String = GITHUB_ACCEPT
    ): WorkflowJobsResponse

    @Streaming
    @GET
    suspend fun jobLogs(
        @Url path: String,
        @Header("Authorization") authorization: String,
        @Header("Accept") accept: String = GITHUB_ACCEPT
    ): ResponseBody

    @GET
    suspend fun artifacts(
        @Url path: String,
        @Header("Authorization") authorization: String,
        @Header("Accept") accept: String = GITHUB_ACCEPT
    ): WorkflowArtifactsResponse

    companion object {
        const val GITHUB_ACCEPT = "application/vnd.github+json"
    }
}

data class GithubCommitAuthor(
    val name: String?,
    val date: String?
)

data class GithubCommitDetails(
    val message: String,
    val author: GithubCommitAuthor?
)

data class GithubCommitItem(
    val sha: String,
    val commit: GithubCommitDetails
)

data class CreatePullRequestRequest(
    val title: String,
    val head: String,
    val base: String,
    val body: String? = null,
    val draft: Boolean = false,
    @SerializedName("maintainer_can_modify")
    val maintainerCanModify: Boolean = true
)

data class GithubPullRequestResponse(
    val number: Long,
    val state: String,
    val title: String,
    @SerializedName("html_url")
    val htmlUrl: String?
)

data class WorkflowRunsResponse(
    @SerializedName("workflow_runs")
    val runs: List<GithubWorkflowRun>
)

data class GithubWorkflowRun(
    val id: Long,
    val name: String?,
    val status: String?,
    val conclusion: String?,
    @SerializedName("head_branch")
    val headBranch: String?,
    @SerializedName("head_sha")
    val headSha: String?,
    @SerializedName("html_url")
    val htmlUrl: String?
) {
    val ciState: CiState
        get() = normalizeCiState(status, conclusion)
}

data class WorkflowJobsResponse(
    val jobs: List<GithubWorkflowJob>
)

data class GithubWorkflowJob(
    val id: Long,
    val name: String,
    val status: String?,
    val conclusion: String?,
    @SerializedName("html_url")
    val htmlUrl: String?
) {
    val ciState: CiState
        get() = normalizeCiState(status, conclusion)
}

data class WorkflowArtifactsResponse(
    val artifacts: List<GithubArtifact>
)

data class GithubArtifact(
    val id: Long,
    val name: String,
    @SerializedName("size_in_bytes")
    val sizeInBytes: Long,
    val expired: Boolean,
    val digest: String?,
    @SerializedName("expires_at")
    val expiresAt: String?
)

class GithubWorkspaceService(
    private val api: GithubWorkspaceApi,
    token: String
) : WorkspaceGateway {
    private val authorization = "Bearer " + token.trim()

    init {
        require(token.isNotBlank()) { "GitHub token is required" }
    }

    suspend fun commits(repoFullName: String, branch: String): List<GithubCommitItem> {
        require(branch.isNotBlank()) { "Branch is required" }
        return api.commits(
            path = commitsPath(repoFullName),
            authorization = authorization,
            branch = branch
        )
    }

    suspend fun createPullRequest(
        repoFullName: String,
        head: String,
        base: String,
        title: String,
        body: String?
    ): GithubPullRequestResponse {
        require(head.startsWith("codex/") && head.removePrefix("codex/").isNotBlank()) {
            "Pull request head must be a codex/* branch"
        }
        require(base.isNotBlank()) { "Base branch is required" }
        require(title.isNotBlank()) { "Pull request title is required" }

        return api.createPullRequest(
            path = pullsPath(repoFullName),
            authorization = authorization,
            body = CreatePullRequestRequest(
                title = title.trim(),
                head = head,
                base = base,
                body = body?.trim()?.takeIf { it.isNotEmpty() }
            )
        )
    }

    suspend fun workflowRuns(
        repoFullName: String,
        branch: String
    ): List<GithubWorkflowRun> {
        require(branch.isNotBlank()) { "Branch is required" }
        return api.workflowRuns(
            path = workflowRunsPath(repoFullName),
            authorization = authorization,
            branch = branch
        ).runs
    }

    suspend fun workflowJobs(
        repoFullName: String,
        runId: Long
    ): List<GithubWorkflowJob> =
        api.workflowJobs(
            path = workflowJobsPath(repoFullName, runId),
            authorization = authorization
        ).jobs

    override suspend fun jobLog(
        repoFullName: String,
        jobId: Long
    ): String =
        api.jobLogs(
            path = workflowJobLogsPath(repoFullName, jobId),
            authorization = authorization
        ).use { it.string() }

    override suspend fun workflowArtifacts(
        repoFullName: String,
        runId: Long
    ): List<GithubArtifact> =
        api.artifacts(
            path = workflowArtifactsPath(repoFullName, runId),
            authorization = authorization
        ).artifacts
}
