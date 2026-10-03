from app.agent_service import (
    AgentService,
    AgentTask,
    InMemoryIdempotencyStore,
    RemoteAgentSession,
    WorkspaceSeed,
)
from app.main import create_app
from fastapi.testclient import TestClient


class Preparer:
    def prepare(self, task):
        return WorkspaceSeed("file", "sha", 1)


class Runtime:
    def create_session(self, task, workspace_seed):
        return RemoteAgentSession("sess_auth", "in_progress", "env")


class Events:
    def stream(self, session_id):
        return iter(())


def test_v1_requires_configured_bearer_token_but_healthz_stays_public():
    service = AgentService(
        Runtime(),
        InMemoryIdempotencyStore(),
        Preparer(),
    )
    app = create_app(
        service=service,
        event_source=Events(),
        access_token="bff-secret",
    )
    client = TestClient(app)
    payload = {
        "repository": "owner/repo",
        "base_branch": "codex/auth",
        "base_sha": "abc123",
        "task": "Do work",
        "model": "gpt-6-astra",
    }

    assert client.get("/healthz").status_code == 200

    missing = client.post(
        "/v1/agents/sessions",
        headers={"Idempotency-Key": "auth-1"},
        json=payload,
    )
    wrong = client.post(
        "/v1/agents/sessions",
        headers={
            "Authorization": "Bearer wrong",
            "Idempotency-Key": "auth-1",
        },
        json=payload,
    )
    accepted = client.post(
        "/v1/agents/sessions",
        headers={
            "Authorization": "Bearer bff-secret",
            "Idempotency-Key": "auth-1",
        },
        json=payload,
    )

    assert missing.status_code == 401
    assert wrong.status_code == 401
    assert accepted.status_code == 201
