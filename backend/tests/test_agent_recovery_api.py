from app.agent_recovery import AgentRecoverySnapshot
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
        return RemoteAgentSession("sess_recovery_api", "in_progress", "env")


class Recovery:
    def recover(self, session_id):
        assert session_id == "sess_recovery_api"
        return AgentRecoverySnapshot(
            session_id=session_id,
            status="idle",
            error=None,
            required_actions=(),
            items=(
                {
                    "id": "item_1",
                    "type": "agent_message",
                    "content": [{"type": "output_text", "text": "done"}],
                },
            ),
        )


class Events:
    def stream(self, session_id):
        return iter(())


def test_recovery_endpoint_is_scoped_to_known_session_and_returns_saved_items():
    service = AgentService(
        runtime=Runtime(),
        idempotency_store=InMemoryIdempotencyStore(),
        workspace_preparer=Preparer(),
        recovery_source=Recovery(),
    )
    app = create_app(service=service, event_source=Events())
    client = TestClient(app)

    created = client.post(
        "/v1/agents/sessions",
        headers={"Idempotency-Key": "recover-task"},
        json={
            "repository": "owner/repo",
            "base_branch": "codex/recovery",
            "base_sha": "abc123",
            "task": "Do work",
            "model": "gpt-6-astra",
        },
    )
    assert created.status_code == 201

    response = client.get(
        "/v1/agents/sessions/sess_recovery_api/recovery"
    )
    unknown = client.get(
        "/v1/agents/sessions/unknown/recovery"
    )

    assert response.status_code == 200
    assert response.json() == {
        "session_id": "sess_recovery_api",
        "status": "idle",
        "error": None,
        "required_actions": [],
        "items": [{
            "id": "item_1",
            "type": "agent_message",
            "content": [{"type": "output_text", "text": "done"}],
        }],
    }
    assert unknown.status_code == 404
