package com.mojealterego.codexandroid.agent

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AgentBffClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: AgentBffClient

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        client = AgentBffClient(
            baseUrl = server.url("/").toString(),
            httpClient = OkHttpClient.Builder()
                .readTimeout(2, TimeUnit.SECONDS)
                .build()
        )
    }

    @After fun tearDown() {
        server.shutdown()
    }

    @Test fun startsSessionWithIdempotencyKey() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(201)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "session_id":"sess_1",
                      "state":"in_progress",
                      "environment_id":"env_1",
                      "repository":"owner/repo",
                      "base_branch":"codex/review",
                      "base_sha":"head123",
                      "events_path":"/v1/agents/sessions/sess_1/events"
                    }
                    """.trimIndent()
                )
        )

        val result = client.startSession(
            StartAgentSessionRequest(
                repository = "owner/repo",
                baseBranch = "codex/review",
                baseSha = "head123",
                task = "Change README",
                model = "gpt-6-astra"
            ),
            idempotencyKey = "android-task-1"
        )

        assertEquals("sess_1", result.sessionId)
        val request = server.takeRequest()
        assertEquals("/v1/agents/sessions", request.path)
        assertEquals("android-task-1", request.getHeader("Idempotency-Key"))
        assertTrue(request.body.readUtf8().contains("\"base_sha\":\"head123\""))
    }

    @Test fun loadsReviewableChanges() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "session_id":"sess_1",
                      "turn_id":"turn_1",
                      "base_branch":"codex/review",
                      "base_sha":"head123",
                      "files":[{
                        "path":"README.md",
                        "operation":"upsert",
                        "mode":"100644",
                        "content":"updated\\n",
                        "diff":"@@ diff",
                        "rename_from":null
                      }]
                    }
                    """.trimIndent()
                )
        )

        val result = client.loadChanges("sess_1")

        assertEquals("turn_1", result.turnId)
        assertEquals("README.md", result.files.single().path)
        assertEquals(
            "/v1/agents/sessions/sess_1/changes",
            server.takeRequest().path
        )
    }

    @Test fun streamsSseEvents() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    "event: agent.session.turn.output_text.delta\n" +
                        "data: {\"delta\":\"hello\"}\n\n" +
                        "event: agent.session.idle\n" +
                        "data: {\"status\":\"idle\"}\n\n"
                )
        )
        val events = mutableListOf<AgentStreamEvent>()

        client.streamEvents("sess_1") { events += it }

        assertEquals(
            listOf(
                "agent.session.turn.output_text.delta",
                "agent.session.idle"
            ),
            events.map { it.type }
        )
        assertEquals("/v1/agents/sessions/sess_1/events", server.takeRequest().path)
    }
    @Test fun steersActiveSessionWithIdempotencyKey() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(202)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"status\":\"accepted\"}")
        )

        client.steer(
            sessionId = "sess_1",
            message = "Keep compatibility.",
            idempotencyKey = "steer-001"
        )

        val request = server.takeRequest()
        assertEquals("/v1/agents/sessions/sess_1/messages", request.path)
        assertEquals("steer-001", request.getHeader("Idempotency-Key"))
        assertTrue(
            request.body.readUtf8().contains(
                "\"message\":\"Keep compatibility.\""
            )
        )
    }

    @Test fun cancelsActiveSessionThroughServerControl() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(202)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"status\":\"accepted\"}")
        )

        client.cancel("sess_1")

        val request = server.takeRequest()
        assertEquals("/v1/agents/sessions/sess_1/cancel", request.path)
        assertEquals("POST", request.method)
    }

}
