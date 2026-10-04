from types import SimpleNamespace

from app.agent_service import AgentTask, WorkspaceSeed
from app.openai_agents_runtime import OpenAIAgentsRuntime


class FakeSessions:
    def __init__(self) -> None:
        self.last_kwargs = None

    def create(self, **kwargs):
        self.last_kwargs = kwargs
        return SimpleNamespace(
            id="sess_seeded_123",
            status="in_progress",
            environment=SimpleNamespace(id="env_seeded_123"),
        )


class FakeClient:
    def __init__(self) -> None:
        self.sessions = FakeSessions()
        self.beta = SimpleNamespace(
            agents=SimpleNamespace(
                sessions=self.sessions,
            )
        )


def test_seeded_workspace_is_mounted_before_initial_agent_task():
    client = FakeClient()
    runtime = OpenAIAgentsRuntime(
        client=client,
        instructions="Edit code only inside the provided workspace.",
    )
    task = AgentTask(
        repo_full_name="mojealterego/Codex-Android",
        base_branch="codex/seeded-runtime-test",
        base_sha="abc123",
        task="Implement the next verified slice.",
        model="gpt-6-astra",
    )
    seed = WorkspaceSeed(
        file_id="file_repo_archive_123",
        sha256="deadbeef",
        size_bytes=4096,
    )

    result = runtime.create_session(task, seed)

    assert result.session_id == "sess_seeded_123"
    request = client.sessions.last_kwargs
    environment = request["environment"]

    assert environment["type"] == "openai_hosted"
    assert environment["network"] == {"access": "disabled"}
    assert environment["files"][0] == {
        "type": "file_id",
        "file_id": "file_repo_archive_123",
        "path": "/workspace/input/repository.tar.gz",
    }
    assert environment["files"][1]["path"] == "/workspace/change_exporter.py"
    assert "git commit --allow-empty -qm baseline" in (
        environment["setup_commands"][0]["command"]
    )

    assert request["input"].startswith(
        "Repository: mojealterego/Codex-Android\n"
        "Pinned base branch: main\n"
        "Pinned base SHA: abc123\n"
        "Workspace: /workspace/repository\n\n"
        "Task:\nImplement the next verified slice."
    )
    assert "/workspace/outputs/changes.json" in request["input"]

    serialized = repr(request).lower()
    assert "github_token" not in serialized
    assert "authorization" not in serialized
