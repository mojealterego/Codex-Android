package com.mojealterego.codexandroid.github

import java.io.File
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GithubArtifactDownloadServiceTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test fun streamsArtifactZipToDestination() {
        kotlinx.coroutines.runBlocking {
            val api = FakeGithubArtifactApi()
            val service = GithubArtifactDownloadService(api, "token-123")
            val destination = File(temp.root, "artifact.zip")

            val result = service.download(
                repoFullName = "mojealterego/Codex-Android",
                artifactId = 987,
                destination = destination
            )

            assertEquals(destination.absolutePath, result.absolutePath)
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), result.readBytes())
            assertEquals(
                workflowArtifactDownloadPath("mojealterego/Codex-Android", 987),
                api.lastPath
            )
            assertEquals("Bearer token-123", api.lastAuthorization)
        }
    }
}

private class FakeGithubArtifactApi : GithubArtifactApi {
    var lastPath: String? = null
    var lastAuthorization: String? = null

    override suspend fun downloadArtifact(
        path: String,
        authorization: String,
        accept: String
    ): ResponseBody {
        lastPath = path
        lastAuthorization = authorization
        return byteArrayOf(1, 2, 3, 4).toResponseBody()
    }
}
