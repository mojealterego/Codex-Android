package com.mojealterego.codexandroid.github

enum class WorkspaceSection {
    FILES,
    COMMITS,
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
