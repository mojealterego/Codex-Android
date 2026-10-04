from app.agent_service import (
    AgentService,
    AgentTask,
    InMemoryIdempotencyStore,
    RemoteAgentSession,
    WorkspaceSeed,
)


class FakeWorkspacePreparer:
    def __init__(self, events):
        self.events = events
        self.calls = 0

    def prepare(self, task: AgentTask) -> WorkspaceSeed:
        self.calls += 1
        self.events.append(("prepare", task.base_sha))
        return WorkspaceSeed(
            file_id="file_seed_123",
            sha256="abc123",
            size_bytes=1024,
        )


class FakeRuntime:
    def __init__(self, events):
        self.events = events
        self.calls = 0
        self.last_seed = None

    def create_session(
        self,
        task: AgentTask,
        workspace_seed: WorkspaceSeed,
    ) -> RemoteAgentSession:
        self.calls += 1
        self.last_seed = workspace_seed
        self.events.append(("runtime", workspace_seed.file_id))
        return RemoteAgentSession(
            session_id="sess_seeded_service_123",
            state="in_progress",
            environment_id="env_seeded_service_123",
        )


def test_service_prepares_workspace_before_runtime_and_only_once_per_idempotency_key():
    events = []
    preparer = FakeWorkspacePreparer(events)
    runtime = FakeRuntime(events)
    service = AgentService(
        runtime=runtime,
        idempotency_store=InMemoryIdempotencyStore(),
        workspace_preparer=preparer,
    )
    task = AgentTask(
        repo_full_name="mojealterego/Codex-Android",
        base_branch="codex/workspace-seed-test",
        base_sha="abc123",
        task="Implement seeded runtime.",
        model="gpt-6-astra",
    )

    first = service.start(task, idempotency_key="seeded-task-1")
    second = service.start(task, idempotency_key="seeded-task-1")

    assert first == second
    assert preparer.calls == 1
    assert runtime.calls == 1
    assert runtime.last_seed.file_id == "file_seed_123"
    assert events == [
        ("prepare", "abc123"),
        ("runtime", "file_seed_123"),
    ]
