from app.agent_service import (
    AgentService,
    AgentTask,
    InMemoryIdempotencyStore,
    RemoteAgentSession,
    WorkspaceSeed,
)
from app.main import create_app
from fastapi.testclient import TestClient


class FakeWorkspacePreparer:
    def __init__(self) -> None:
        self.calls = 0

    def prepare(self, task: AgentTask) -> WorkspaceSeed:
        self.calls += 1
        return WorkspaceSeed(
            file_id="file_http_workspace_123",
            sha256="feedface",
            size_bytes=2048,
        )


class FakeRuntime:
    def __init__(self) -> None:
        self.calls = 0

    def create_session(
        self,
        task: AgentTask,
        workspace_seed: WorkspaceSeed,
    ) -> RemoteAgentSession:
        self.calls += 1
        assert workspace_seed.file_id == "file_http_workspace_123"
        return RemoteAgentSession(
            session_id="sess_http_123",
            state="in_progress",
            environment_id="env_http_123",
        )


class FakeEventSource:
    def stream(self, session_id: str):
        assert session_id == "sess_http_123"
        yield {
            "type": "agent.session.created",
            "session_id": session_id,
        }
        yield {
            "type": "agent.session.idle",
            "session_id": session_id,
        }


def build_client():
    runtime = FakeRuntime()
    preparer = FakeWorkspacePreparer()
    service = AgentService(
        runtime,
        InMemoryIdempotencyStore(),
        preparer,
    )
    app = create_app(service=service, event_source=FakeEventSource())
    return TestClient(app), runtime, preparer


def test_healthz():
    client, _, _ = build_client()

    response = client.get("/healthz")

    assert response.status_code == 200
    assert response.json() == {"status": "ok"}


def test_create_session_requires_idempotency_and_returns_events_path():
    client, runtime, preparer = build_client()
    payload = {
        "repository": "mojealterego/Codex-Android",
        "base_branch": "codex/http-test",
        "base_sha": "abc123",
        "task": "Add mobile agent UI",
        "model": "gpt-6-astra",
    }

    first = client.post(
        "/v1/agents/sessions",
        headers={"Idempotency-Key": "task-http-1"},
        json=payload,
    )
    second = client.post(
        "/v1/agents/sessions",
        headers={"Idempotency-Key": "task-http-1"},
        json={**payload, "task": "retry body must not start another session"},
    )

    assert first.status_code == 201
    assert second.status_code == 201
    assert first.json() == second.json()
    assert first.json()["session_id"] == "sess_http_123"
    assert first.json()["events_path"] == (
        "/v1/agents/sessions/sess_http_123/events"
    )
    assert runtime.calls == 1
    assert preparer.calls == 1

    missing = client.post("/v1/agents/sessions", json=payload)
    assert missing.status_code == 422


def test_streams_agent_events_as_sse_only_for_known_session():
    client, _, _ = build_client()
    started = client.post(
        "/v1/agents/sessions",
        headers={"Idempotency-Key": "task-stream-1"},
        json={
            "repository": "mojealterego/Codex-Android",
            "base_branch": "codex/http-test",
            "base_sha": "abc123",
            "task": "Stream this session",
            "model": "gpt-5.6-sol",
        },
    )
    assert started.status_code == 201

    response = client.get("/v1/agents/sessions/sess_http_123/events")

    assert response.status_code == 200
    assert response.headers["content-type"].startswith("text/event-stream")
    assert "event: agent.session.created" in response.text
    assert '"session_id":"sess_http_123"' in response.text
    assert "event: agent.session.idle" in response.text


def test_rejects_event_stream_for_unknown_session():
    client, _, _ = build_client()

    response = client.get("/v1/agents/sessions/foreign_session/events")

    assert response.status_code == 404
