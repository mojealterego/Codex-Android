package com.mojealterego.codexandroid.agent

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AgentBffDiagnosticsTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() {
        server.shutdown()
    }

    @Test fun loadsAuthenticatedRuntimeDiagnostics() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "status":"ok",
                      "storage_backend":"postgres",
                      "persistent_storage":true,
                      "agents_api":"configured",
                      "github_private_access":false
                    }
                    """.trimIndent()
                )
        )

        val client = AgentBffClient(
            baseUrl = server.url("/").toString(),
            httpClient = OkHttpClient(),
            accessToken = "bff-secret"
        )

        val result = client.loadDiagnostics()
        val request = server.takeRequest()

        assertEquals("/v1/system/diagnostics", request.path)
        assertEquals("Bearer bff-secret", request.getHeader("Authorization"))
        assertEquals("ok", result.status)
        assertEquals("postgres", result.storageBackend)
        assertTrue(result.persistentStorage)
        assertEquals("configured", result.agentsApi)
        assertFalse(result.githubPrivateAccess)
    }
}
