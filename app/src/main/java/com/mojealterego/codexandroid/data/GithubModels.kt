package com.mojealterego.codexandroid.data

import com.google.gson.annotations.SerializedName

data class GithubRepo(
    val id: Long,
    val name: String,
    @SerializedName("full_name") val fullName: String,
    val private: Boolean,
    @SerializedName("default_branch") val defaultBranch: String,
    val language: String?,
    val description: String?
)

fun filterRepositories(repositories: List<GithubRepo>, query: String): List<GithubRepo> {
    val q = query.trim()
    if (q.isBlank()) return repositories
    return repositories.filter {
        it.name.contains(q, ignoreCase = true) ||
            it.fullName.contains(q, ignoreCase = true) ||
            (it.description?.contains(q, ignoreCase = true) == true)
    }
}
