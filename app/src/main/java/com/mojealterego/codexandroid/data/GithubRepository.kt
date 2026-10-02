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
}
