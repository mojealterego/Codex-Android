package com.mojealterego.codexandroid.github

enum class CiState {
    WAITING_FOR_RUN,
    QUEUED,
    IN_PROGRESS,
    SUCCESS,
    FAILURE,
    CANCELLED,
    TIMED_OUT,
    ACTION_REQUIRED,
    UNKNOWN
}

fun normalizeCiState(status: String?, conclusion: String?): CiState =
    when (status) {
        "queued", "waiting", "pending", "requested" -> CiState.QUEUED
        "in_progress" -> CiState.IN_PROGRESS
        "completed" -> when (conclusion) {
            "success" -> CiState.SUCCESS
            "failure", "startup_failure" -> CiState.FAILURE
            "cancelled" -> CiState.CANCELLED
            "timed_out" -> CiState.TIMED_OUT
            "action_required" -> CiState.ACTION_REQUIRED
            else -> CiState.UNKNOWN
        }
        null, "" -> CiState.WAITING_FOR_RUN
        else -> CiState.UNKNOWN
    }

fun commitsPath(repoFullName: String): String =
    "repos/" + repoFullName.trim('/') + "/commits"

fun pullsPath(repoFullName: String): String =
    "repos/" + repoFullName.trim('/') + "/pulls"

fun workflowRunsPath(repoFullName: String): String =
    "repos/" + repoFullName.trim('/') + "/actions/runs"

fun workflowJobsPath(repoFullName: String, runId: Long): String =
    "repos/" + repoFullName.trim('/') + "/actions/runs/" + runId + "/jobs"

fun workflowArtifactsPath(repoFullName: String, runId: Long): String =
    "repos/" + repoFullName.trim('/') + "/actions/runs/" + runId + "/artifacts"

fun workflowJobLogsPath(repoFullName: String, jobId: Long): String =
    "repos/" + repoFullName.trim('/') + "/actions/jobs/" + jobId + "/logs"

fun workflowArtifactDownloadPath(repoFullName: String, artifactId: Long): String =
    "repos/" + repoFullName.trim('/') + "/actions/artifacts/" + artifactId + "/zip"
