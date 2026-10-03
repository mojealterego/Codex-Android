from app.agent_service import (
    AgentService,
    AgentTask,
    InMemoryIdempotencyStore,
    RemoteAgentSession,
    WorkspaceSeed,
)


class Preparer:
    def prepare(self, task):
        return WorkspaceSeed("file", "sha", 1)


class Runtime:
    def create_session(self, task, workspace_seed):
        return RemoteAgentSession("sess_control", "in_progress", "env")


class Control:
    def __init__(self):
        self.steers = []
        self.cancels = []

    def steer(self, session_id, message, idempotency_key):
        self.steers.append((session_id, message, idempotency_key))

    def cancel(self, session_id):
        self.cancels.append(session_id)


def build_service():
    control = Control()
    service = AgentService(
        runtime=Runtime(),
        idempotency_store=InMemoryIdempotencyStore(),
        workspace_preparer=Preparer(),
        agent_control=control,
    )
    service.start(
        AgentTask(
            repo_full_name="owner/repo",
            base_branch="codex/control",
            base_sha="abc123",
            task="Do work",
            model="gpt-6-astra",
        ),
        idempotency_key="task-control",
    )
    return service, control


def test_service_steers_and_cancels_known_session_only():
    service, control = build_service()

    service.steer(
        "sess_control",
        "Preserve compatibility",
        idempotency_key="steer-1",
    )
    service.cancel("sess_control")

    assert control.steers == [
        ("sess_control", "Preserve compatibility", "steer-1")
    ]
    assert control.cancels == ["sess_control"]

    for operation in [
        lambda: service.steer(
            "unknown",
            "message",
            idempotency_key="steer-2",
        ),
        lambda: service.cancel("unknown"),
    ]:
        try:
            operation()
            raise AssertionError("Expected ValueError")
        except ValueError as error:
            assert "unknown agent session" in str(error).lower()
