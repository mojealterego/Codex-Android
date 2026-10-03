from types import SimpleNamespace

from app.agent_service import AgentTask
from app.openai_agents_runtime import OpenAIAgentsRuntime


class FakeSessions:
    def __init__(self) -> None:
        self.last_kwargs = None

    def create(self, **kwargs):
        self.last_kwargs = kwargs
        return SimpleNamespace(
            id="sess_openai_123",
            status="idle",
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


def test_creates_idle_openai_hosted_session_without_starting_task():
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

    result = runtime.create_session(task)

    assert result.session_id == "sess_openai_123"
    assert result.state == "idle"
    assert result.environment_id == "env_openai_123"

    request = client.sessions.last_kwargs
    assert request["environment"] == {
        "type": "openai_hosted",
        "network": {"access": "disabled"},
    }
    assert request["agent"]["model"] == "gpt-6-astra"
    assert request["agent"]["instructions"] == (
        "Edit code only inside the provided workspace."
    )
    assert request["metadata"] == {
        "repository": "mojealterego/Codex-Android",
        "base_branch": "main",
        "base_sha": "abc123",
    }

    # Do not start the turn before repository files are seeded.
    assert "input" not in request

    # A GitHub credential must never be injected into the agent session.
    serialized = repr(request).lower()
    assert "github_token" not in serialized
    assert "authorization" not in serialized
