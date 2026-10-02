package com.mojealterego.codexandroid.data

import org.junit.Assert.assertEquals
import org.junit.Test

class RepoPathTest {
    @Test fun rootContentsUrlUsesRepositoryFullName() {
        assertEquals("repos/mojealterego/ODYN-AI/contents/", repositoryContentsPath("mojealterego/ODYN-AI", ""))
    }

    @Test fun nestedContentsUrlPreservesPath() {
        assertEquals("repos/mojealterego/ODYN-AI/contents/app/src", repositoryContentsPath("mojealterego/ODYN-AI", "app/src"))
    }
}
