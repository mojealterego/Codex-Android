from app.agent_service import (
    AgentService,
    AgentSessionView,
    InMemoryIdempotencyStore,
)


SESSION = AgentSessionView(
    session_id="sess_remote",
    state="idle",
    environment_id="env_remote",
    repository="mojealterego/Codex-Android",
    base_branch="codex/recovery",
    base_sha="abc123",
    events_path="/v1/agents/sessions/sess_remote/events",
)


class Resolver:
    def __init__(self, session=SESSION):
        self.session = session
        self.calls = []

    def resolve(self, session_id):
        self.calls.append(session_id)
        return self.session


class Recovery:
    def recover(self, session_id):
        return {"session_id": session_id, "status": "idle"}


class Changes:
    def collect(self, session):
        return ("changes", session.session_id, session.base_sha)


class Control:
    def __init__(self):
        self.steers = []
        self.cancels = []

    def steer(self, session_id, message, idempotency_key):
        self.steers.append((session_id, message, idempotency_key))

    def cancel(self, session_id):
        self.cancels.append(session_id)


class NeverUsed:
    def __getattr__(self, name):
        raise AssertionError(name + " should not be used")


def service(resolver):
    control = Control()
    instance = AgentService(
        runtime=NeverUsed(),
        idempotency_store=InMemoryIdempotencyStore(),
        workspace_preparer=NeverUsed(),
        change_collector=Changes(),
        agent_control=control,
        recovery_source=Recovery(),
        session_resolver=resolver,
    )
    return instance, control


def test_rehydrates_missing_local_session_for_all_session_operations():
    resolver = Resolver()
    subject, control = service(resolver)

    assert subject.resolve_session("sess_remote") == SESSION
    assert subject.recover("sess_remote") == {
        "session_id": "sess_remote",
        "status": "idle",
    }
    assert subject.collect_changes("sess_remote") == (
        "changes",
        "sess_remote",
        "abc123",
    )

    subject.steer("sess_remote", "continue", "steer-key")
    subject.cancel("sess_remote")

    assert control.steers == [("sess_remote", "continue", "steer-key")]
    assert control.cancels == ["sess_remote"]
    assert resolver.calls == ["sess_remote"] * 5


def test_unknown_remote_session_stays_unknown():
    subject, _ = service(Resolver(session=None))

    try:
        subject.resolve_session("foreign_session")
        raise AssertionError("Expected ValueError")
    except ValueError as error:
        assert "unknown agent session" in str(error).lower()
