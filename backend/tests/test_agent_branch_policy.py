from app.agent_service import (
    AgentService,
    AgentTask,
    InMemoryIdempotencyStore,
)


class NeverCalled:
    def __init__(self):
        self.calls = 0

    def prepare(self, task):
        self.calls += 1
        raise AssertionError("workspace preparation must not run")

    def create_session(self, task, workspace_seed):
        self.calls += 1
        raise AssertionError("runtime must not run")


def test_backend_rejects_agent_session_outside_codex_branch():
    runtime = NeverCalled()
    preparer = NeverCalled()
    service = AgentService(
        runtime=runtime,
        idempotency_store=InMemoryIdempotencyStore(),
        workspace_preparer=preparer,
    )

    try:
        service.start(
            AgentTask(
                repo_full_name="mojealterego/Codex-Android",
                base_branch="main",
                base_sha="abc123",
                task="Do not allow direct main work",
                model="gpt-5.6-sol",
            ),
            idempotency_key="main-not-allowed",
        )
        raise AssertionError("Expected ValueError")
    except ValueError as error:
        assert "codex/*" in str(error)

    assert preparer.calls == 0
    assert runtime.calls == 0
