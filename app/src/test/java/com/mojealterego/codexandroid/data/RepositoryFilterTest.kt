package com.mojealterego.codexandroid.data

import org.junit.Assert.assertEquals
import org.junit.Test

class RepositoryFilterTest {
    private val repos = listOf(
        GithubRepo(1, "ODYN-AI", "mojealterego/ODYN-AI", false, "main", "Kotlin", null),
        GithubRepo(2, "Ai-game-factory", "mojealterego/Ai-game-factory", true, "main", "Python", null)
    )

    @Test fun searchMatchesNameCaseInsensitively() {
        assertEquals(listOf("ODYN-AI"), filterRepositories(repos, "odyn").map { it.name })
    }

    @Test fun blankSearchReturnsEverything() {
        assertEquals(repos, filterRepositories(repos, ""))
    }
}
