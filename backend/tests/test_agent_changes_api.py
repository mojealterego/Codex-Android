from app.agent_changes import AgentChangeSet, AgentFileChange
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
        return RemoteAgentSession("sess_api_changes", "in_progress", "env")


class Collector:
    def collect(self, session):
        return AgentChangeSet(
            session_id=session.session_id,
            turn_id="turn_api",
            base_branch=session.base_branch,
            base_sha=session.base_sha,
            files=(
                AgentFileChange(
                    path="README.md",
                    operation="upsert",
                    mode="100644",
                    content="hello\n",
                    diff="@@ -1 +1 @@",
                ),
            ),
        )


class Events:
    def stream(self, session_id):
        return iter(())


def test_changes_endpoint_returns_reviewable_change_set():
    service = AgentService(
        Runtime(),
        InMemoryIdempotencyStore(),
        Preparer(),
        Collector(),
    )
    app = create_app(service=service, event_source=Events())
    client = TestClient(app)

    created = client.post(
        "/v1/agents/sessions",
        headers={"Idempotency-Key": "changes-api-1"},
        json={
            "repository": "owner/repo",
            "base_branch": "codex/review",
            "base_sha": "base123",
            "task": "Edit README",
            "model": "gpt-6-astra",
        },
    )
    assert created.status_code == 201

    response = client.get(
        "/v1/agents/sessions/sess_api_changes/changes"
    )

    assert response.status_code == 200
    body = response.json()
    assert body["session_id"] == "sess_api_changes"
    assert body["turn_id"] == "turn_api"
    assert body["base_branch"] == "codex/review"
    assert body["base_sha"] == "base123"
    assert body["files"] == [{
        "path": "README.md",
        "operation": "upsert",
        "mode": "100644",
        "content": "hello\n",
        "diff": "@@ -1 +1 @@",
        "rename_from": None,
    }]
