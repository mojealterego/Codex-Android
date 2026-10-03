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
        return RemoteAgentSession("sess_api_control", "in_progress", "env")


class Control:
    def __init__(self):
        self.steers = []
        self.cancels = []

    def steer(self, session_id, message, idempotency_key):
        self.steers.append((session_id, message, idempotency_key))

    def cancel(self, session_id):
        self.cancels.append(session_id)


class Events:
    def stream(self, session_id):
        return iter(())


def build_client():
    control = Control()
    service = AgentService(
        runtime=Runtime(),
        idempotency_store=InMemoryIdempotencyStore(),
        workspace_preparer=Preparer(),
        agent_control=control,
    )
    app = create_app(service=service, event_source=Events())
    client = TestClient(app)
    created = client.post(
        "/v1/agents/sessions",
        headers={"Idempotency-Key": "control-task"},
        json={
            "repository": "owner/repo",
            "base_branch": "codex/control",
            "base_sha": "abc123",
            "task": "Do work",
            "model": "gpt-6-astra",
        },
    )
    assert created.status_code == 201
    return client, control


def test_steer_endpoint_requires_idempotency_and_forwards_message():
    client, control = build_client()

    response = client.post(
        "/v1/agents/sessions/sess_api_control/messages",
        headers={"Idempotency-Key": "steer-http-1"},
        json={"message": "Keep the public API unchanged."},
    )

    assert response.status_code == 202
    assert response.json() == {"status": "accepted"}
    assert control.steers == [
        (
            "sess_api_control",
            "Keep the public API unchanged.",
            "steer-http-1",
        )
    ]

    missing = client.post(
        "/v1/agents/sessions/sess_api_control/messages",
        json={"message": "Retry"},
    )
    assert missing.status_code == 422


def test_cancel_endpoint_forwards_cancel_event():
    client, control = build_client()

    response = client.post(
        "/v1/agents/sessions/sess_api_control/cancel"
    )

    assert response.status_code == 202
    assert response.json() == {"status": "accepted"}
    assert control.cancels == ["sess_api_control"]


def test_control_endpoints_reject_unknown_session():
    client, _ = build_client()

    steer = client.post(
        "/v1/agents/sessions/unknown/messages",
        headers={"Idempotency-Key": "steer-unknown"},
        json={"message": "hello"},
    )
    cancel = client.post(
        "/v1/agents/sessions/unknown/cancel"
    )

    assert steer.status_code == 404
    assert cancel.status_code == 404
