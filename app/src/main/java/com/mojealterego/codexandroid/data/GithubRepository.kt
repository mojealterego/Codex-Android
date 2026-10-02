package com.mojealterego.codexandroid.data

class GithubRepository(private val api: GithubApi) {
    suspend fun loadAll(token: String): List<GithubRepo> {
        require(token.isNotBlank()) { "GitHub token is required" }
        val all = mutableListOf<GithubRepo>()
        var page = 1
        while (true) {
            val batch = api.repositories(authorization = "Bearer " + token, page = page)
            all += batch
            if (batch.size < 100) break
            page++
        }
        return all.distinctBy { it.id }
    }

    suspend fun branches(token: String, repo: GithubRepo): List<GithubBranch> =
        api.branches(repositoryBranchesPath(repo.fullName), "Bearer " + token)

    suspend fun contents(token: String, repo: GithubRepo, branch: String, path: String = ""): List<RepoContent> =
        api.contents(repositoryContentsPath(repo.fullName, path), "Bearer " + token, ref = branch)
            .sortedWith(compareBy<RepoContent> { it.type != "dir" }.thenBy { it.name.lowercase() })

    suspend fun file(token: String, repo: GithubRepo, branch: String, path: String): GithubFileContent =
        api.file(repositoryContentsPath(repo.fullName, path), "Bearer " + token, ref = branch)

    suspend fun updateFile(token: String, repo: GithubRepo, branch: String, file: GithubFileContent, text: String, message: String): UpdateFileResponse {
        require(message.isNotBlank()) { "Commit message is required" }
        val encoded = java.util.Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))
        return api.updateFile(
            repositoryContentsPath(repo.fullName, file.path),
            "Bearer " + token,
            body = UpdateFileRequest(message.trim(), encoded, file.sha, branch)
        )
    }
}
