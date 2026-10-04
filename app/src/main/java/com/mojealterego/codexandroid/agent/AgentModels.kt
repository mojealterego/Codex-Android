package com.mojealterego.codexandroid.agent

import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName
import com.mojealterego.codexandroid.git.ChangeSetDraft
import com.mojealterego.codexandroid.git.FileDraft
import com.mojealterego.codexandroid.git.FileOperation

data class StartAgentSessionRequest(
    val repository: String,
    @SerializedName("base_branch") val baseBranch: String,
    @SerializedName("base_sha") val baseSha: String,
    val task: String,
    val model: String
)

data class AgentSessionResponse(
    @SerializedName("session_id") val sessionId: String,
    val state: String,
    @SerializedName("environment_id") val environmentId: String?,
    val repository: String,
    @SerializedName("base_branch") val baseBranch: String,
    @SerializedName("base_sha") val baseSha: String,
    @SerializedName("events_path") val eventsPath: String
)

data class AgentRecoveryResponse(
    @SerializedName("session_id") val sessionId: String,
    val status: String,
    val error: String?,
    @SerializedName("required_actions") val requiredActions: List<JsonElement>,
    val items: List<JsonElement>
)

data class AgentFileChangeResponse(
    val path: String,
    val operation: String,
    val mode: String,
    val content: String?,
    val diff: String,
    @SerializedName("rename_from") val renameFrom: String?
)

data class AgentChangeSetResponse(
    @SerializedName("session_id") val sessionId: String,
    @SerializedName("turn_id") val turnId: String,
    @SerializedName("base_branch") val baseBranch: String,
    @SerializedName("base_sha") val baseSha: String,
    val files: List<AgentFileChangeResponse>
)

data class AgentStreamEvent(
    val type: String,
    val data: String
)

data class AgentRuntimeDiagnostics(
    val status: String,
    @SerializedName("storage_backend") val storageBackend: String,
    @SerializedName("persistent_storage") val persistentStorage: Boolean,
    @SerializedName("agents_api") val agentsApi: String,
    @SerializedName("github_private_access") val githubPrivateAccess: Boolean
)

fun AgentChangeSetResponse.toChangeSetDraft(
    targetBranch: String,
    currentHeadSha: String,
    commitMessage: String
): ChangeSetDraft {
    require(baseBranch == targetBranch) {
        "Agent changes were produced for a different branch"
    }
    require(baseSha == currentHeadSha) {
        "Branch HEAD changed after the agent session started"
    }
    require(targetBranch.startsWith("codex/") &&
        targetBranch.removePrefix("codex/").isNotBlank()) {
        "Agent changes may only be published to codex/* branches"
    }
    require(commitMessage.isNotBlank()) {
        "Commit message is required"
    }
    require(files.isNotEmpty()) {
        "Agent produced no reviewable changes"
    }

    val drafts = files.map { file ->
        val operation = when (file.operation.lowercase()) {
            "upsert" -> FileOperation.UPSERT
            "delete" -> FileOperation.DELETE
            "rename" -> FileOperation.RENAME
            else -> throw IllegalArgumentException(
                "Unsupported agent file operation: " + file.operation
            )
        }

        FileDraft(
            path = file.path,
            operation = operation,
            baseBlobSha = null,
            mode = file.mode,
            draftContent = file.content,
            renameFrom = file.renameFrom
        )
    }

    return ChangeSetDraft(
        targetBranch = targetBranch,
        expectedHeadSha = baseSha,
        commitMessage = commitMessage.trim(),
        files = drafts
    ).validated()
}
