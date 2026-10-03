from app.agent_service import (
    AgentService,
    AgentTask,
    AgentSessionView,
    RemoteAgentSession,
    WorkspaceSeed,
)
from app.main import create_app
from fastapi.testclient import TestClient


class BusyStore:
    def get(self, key):
        return None

    def try_claim(self, key):
        return False

    def release_claim(self, key):
        return None

    def get_by_session_id(self, session_id):
        return None

    def put_if_absent(self, key, value):
        raise AssertionError("must not store while claim is busy")


class Preparer:
    def prepare(self, task):
        raise AssertionError("must not prepare workspace while claim is busy")


class Runtime:
    def create_session(self, task, workspace_seed):
        raise AssertionError("must not create session while claim is busy")


class Events:
    def stream(self, session_id):
        return iter(())


def test_concurrent_idempotency_key_returns_409_not_duplicate_runtime():
    service = AgentService(
        runtime=Runtime(),
        idempotency_store=BusyStore(),
        workspace_preparer=Preparer(),
    )
    client = TestClient(
        create_app(service=service, event_source=Events())
    )

    response = client.post(
        "/v1/agents/sessions",
        headers={"Idempotency-Key": "same-key"},
        json={
            "repository": "owner/repo",
            "base_branch": "codex/idempotent",
            "base_sha": "abc123",
            "task": "Do work",
            "model": "gpt-6-astra",
        },
    )

    assert response.status_code == 409
    assert "already in progress" in response.json()["detail"].lower()
