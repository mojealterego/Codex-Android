package com.mojealterego.codexandroid.git

enum class FileOperation {
    UPSERT,
    DELETE,
    RENAME
}

data class FileDraft(
    val path: String,
    val operation: FileOperation,
    val baseBlobSha: String?,
    val mode: String,
    val draftContent: String?,
    val renameFrom: String? = null
)

data class ChangeSetDraft(
    val targetBranch: String,
    val expectedHeadSha: String,
    val commitMessage: String,
    val files: List<FileDraft>
) {
    fun validated(): ChangeSetDraft {
        require(targetBranch.startsWith("codex/") && targetBranch.removePrefix("codex/").isNotBlank()) {
            "ChangeSet writes are restricted to codex/* branches"
        }
        require(expectedHeadSha.isNotBlank()) { "Expected branch HEAD SHA is required" }
        require(commitMessage.isNotBlank()) { "Commit message is required" }
        require(files.isNotEmpty()) { "At least one file change is required" }

        val normalizedPaths = files.map { it.path.trim('/') }
        require(normalizedPaths.none { it.isBlank() }) { "File path cannot be blank" }
        require(normalizedPaths.distinct().size == normalizedPaths.size) {
            "Duplicate file paths are not allowed"
        }

        files.forEach { file ->
            require(file.mode in VALID_GIT_MODES) { "Unsupported git mode: " + file.mode }
            when (file.operation) {
                FileOperation.UPSERT -> require(file.draftContent != null) {
                    "UPSERT requires draft content"
                }
                FileOperation.DELETE -> Unit
                FileOperation.RENAME -> {
                    require(!file.renameFrom.isNullOrBlank()) { "RENAME requires source path" }
                    require(file.draftContent != null) { "RENAME requires draft content" }
                }
            }
        }
        return this
    }

    companion object {
        private val VALID_GIT_MODES = setOf("100644", "100755", "120000", "160000")
    }
}
