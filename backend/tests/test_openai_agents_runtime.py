from types import SimpleNamespace

from app.agent_service import AgentTask, WorkspaceSeed
from app.openai_agents_runtime import OpenAIAgentsRuntime


class FakeSessions:
    def __init__(self) -> None:
        self.last_kwargs = None

    def create(self, **kwargs):
        self.last_kwargs = kwargs
        return SimpleNamespace(
            id="sess_openai_123",
            status="in_progress",
            environment=SimpleNamespace(id="env_openai_123"),
        )


class FakeClient:
    def __init__(self) -> None:
        self.sessions = FakeSessions()
        self.beta = SimpleNamespace(
            agents=SimpleNamespace(
                sessions=self.sessions,
            )
        )


def test_creates_openai_hosted_session_from_seeded_workspace_without_credentials():
    client = FakeClient()
    runtime = OpenAIAgentsRuntime(
        client=client,
        instructions="Edit code only inside the provided workspace.",
    )
    task = AgentTask(
        repo_full_name="mojealterego/Codex-Android",
        base_branch="main",
        base_sha="abc123",
        task="Add agent screen",
        model="gpt-6-astra",
    )
    seed = WorkspaceSeed(
        file_id="file_repo_123",
        sha256="sha256-repo-123",
        size_bytes=4096,
    )

    result = runtime.create_session(task, seed)

    assert result.session_id == "sess_openai_123"
    assert result.state == "in_progress"
    assert result.environment_id == "env_openai_123"

    request = client.sessions.last_kwargs
    assert request["environment"]["type"] == "openai_hosted"
    assert request["environment"]["network"] == {"access": "disabled"}
    assert request["environment"]["files"][0] == {
        "type": "file_id",
        "file_id": "file_repo_123",
        "path": "/workspace/input/repository.tar.gz",
    }
    assert request["environment"]["files"][1]["type"] == "inline"
    assert request["environment"]["files"][1]["path"] == (
        "/workspace/change_exporter.py"
    )
    assert request["agent"]["model"] == "gpt-6-astra"
    assert request["agent"]["instructions"] == (
        "Edit code only inside the provided workspace."
    )
    assert request["metadata"] == {
        "repository": "mojealterego/Codex-Android",
        "base_branch": "main",
        "base_sha": "abc123",
        "workspace_sha256": "sha256-repo-123",
    }
    assert "Task:\nAdd agent screen" in request["input"]
    assert "/workspace/outputs/changes.json" in request["input"]

    serialized = repr(request).lower()
    assert "github_token" not in serialized
    assert "authorization" not in serialized
