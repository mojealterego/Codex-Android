package com.mojealterego.codexandroid.github

import org.junit.Assert.assertEquals
import org.junit.Test

class GithubWorkflowModelsTest {
    @Test fun normalizesWorkflowStates() {
        assertEquals(CiState.QUEUED, normalizeCiState("queued", null))
        assertEquals(CiState.IN_PROGRESS, normalizeCiState("in_progress", null))
        assertEquals(CiState.SUCCESS, normalizeCiState("completed", "success"))
        assertEquals(CiState.FAILURE, normalizeCiState("completed", "failure"))
        assertEquals(CiState.CANCELLED, normalizeCiState("completed", "cancelled"))
        assertEquals(CiState.TIMED_OUT, normalizeCiState("completed", "timed_out"))
        assertEquals(CiState.ACTION_REQUIRED, normalizeCiState("completed", "action_required"))
        assertEquals(CiState.UNKNOWN, normalizeCiState("completed", "neutral"))
    }

    @Test fun buildsRepositoryWorkflowPaths() {
        val repo = "mojealterego/Codex-Android"
        assertEquals("repos/$repo/commits", commitsPath(repo))
        assertEquals("repos/$repo/pulls", pullsPath(repo))
        assertEquals("repos/$repo/actions/runs", workflowRunsPath(repo))
        assertEquals("repos/$repo/actions/runs/123/jobs", workflowJobsPath(repo, 123))
        assertEquals("repos/$repo/actions/runs/123/artifacts", workflowArtifactsPath(repo, 123))
        assertEquals("repos/$repo/actions/jobs/456/logs", workflowJobLogsPath(repo, 456))
        assertEquals("repos/$repo/actions/artifacts/789/zip", workflowArtifactDownloadPath(repo, 789))
    }
}
