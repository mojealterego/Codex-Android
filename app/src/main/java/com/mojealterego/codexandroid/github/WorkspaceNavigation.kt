package com.mojealterego.codexandroid.github

enum class WorkspaceSection {
    FILES,
    COMMITS,
    AGENT,
    CI,
    PULL_REQUEST
}

fun canCreatePullRequest(
    head: String,
    base: String,
    title: String
): Boolean =
    head.startsWith("codex/") &&
        head.removePrefix("codex/").isNotBlank() &&
        head != base &&
        title.isNotBlank()

fun canStartAgent(
    branch: String,
    headSha: String,
    backendUrl: String,
    task: String,
    runtimeReady: Boolean
): Boolean =
    branch.startsWith("codex/") &&
        branch.removePrefix("codex/").isNotBlank() &&
        headSha.isNotBlank() &&
        backendUrl.isNotBlank() &&
        task.isNotBlank() &&
        runtimeReady
