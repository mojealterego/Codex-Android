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

data class RepoContent(
    val name: String,
    val path: String,
    val type: String,
    val size: Long = 0,
    @SerializedName("download_url") val downloadUrl: String? = null
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

fun repositoryContentsPath(fullName: String, path: String): String {
    val clean = path.trim('/')
    return "repos/" + fullName + "/contents/" + clean
}


data class GithubFileContent(
    val name: String,
    val path: String,
    val sha: String,
    val encoding: String,
    val content: String
) {
    fun decodedText(): String {
        require(encoding == "base64") { "Unsupported GitHub content encoding: " + encoding }
        val clean = content.filterNot { it.isWhitespace() }
        return String(java.util.Base64.getDecoder().decode(clean), Charsets.UTF_8)
    }
}


data class UpdateFileRequest(
    val message: String,
    val content: String,
    val sha: String,
    val branch: String
)

data class UpdateFileResponse(val content: GithubUpdatedContent)
data class GithubUpdatedContent(val sha: String)
