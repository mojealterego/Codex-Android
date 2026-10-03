from dataclasses import replace

from app.agent_service import (
    AgentService,
    AgentTask,
    InMemoryIdempotencyStore,
    RemoteAgentSession,
)


class FakeRuntime:
    def __init__(self) -> None:
        self.calls = []

    def create_session(self, task: AgentTask) -> RemoteAgentSession:
        self.calls.append(task)
        return RemoteAgentSession(
            session_id="sess_123",
            state="running",
            environment_id="env_123",
        )


def test_start_session_is_idempotent_and_preserves_repo_base():
    runtime = FakeRuntime()
    service = AgentService(runtime, InMemoryIdempotencyStore())

    task = AgentTask(
        repo_full_name="mojealterego/Codex-Android",
        base_branch="main",
        base_sha="abc123",
        task="Add agent runtime",
        model="gpt-6-astra",
    )

    first = service.start(task, idempotency_key="task-001")
    second = service.start(
        replace(task, task="this retry must not create another session"),
        idempotency_key="task-001",
    )

    assert first == second
    assert first.session_id == "sess_123"
    assert first.events_path == "/v1/agents/sessions/sess_123/events"
    assert first.repository == "mojealterego/Codex-Android"
    assert first.base_branch == "main"
    assert first.base_sha == "abc123"
    assert len(runtime.calls) == 1
    assert runtime.calls[0] == task


def test_start_rejects_missing_idempotency_key():
    service = AgentService(FakeRuntime(), InMemoryIdempotencyStore())
    task = AgentTask(
        repo_full_name="owner/repo",
        base_branch="main",
        base_sha="abc123",
        task="Do work",
        model="gpt-6-astra",
    )

    try:
        service.start(task, idempotency_key="  ")
        raise AssertionError("Expected ValueError")
    except ValueError:
        pass
