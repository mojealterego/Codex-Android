import json
from types import SimpleNamespace

from app.agent_changes import AgentChangeSet, AgentFileChange
from app.agent_service import AgentSessionView
from app.rehydration_smoke import run_rehydration_smoke


class Resolver:
    def __init__(self, session):
        self.session = session
        self.calls = []

    def resolve(self, session_id):
        self.calls.append(session_id)
        return self.session


class Collector:
    def __init__(self, change_set):
        self.change_set = change_set
        self.calls = []

    def collect(self, session):
        self.calls.append(session)
        return self.change_set


def owned_session():
    return AgentSessionView(
        session_id="sess_existing",
        state="idle",
        environment_id="env_existing",
        repository="mojealterego/Codex-Android",
        base_branch="codex/agent-runtime-p2",
        base_sha="abc123",
        events_path="/v1/agents/sessions/sess_existing/events",
    )


def expected_changes():
    return AgentChangeSet(
        session_id="sess_existing",
        turn_id="turn_existing",
        base_branch="codex/agent-runtime-p2",
        base_sha="abc123",
        files=(
            AgentFileChange(
                path="SMOKE_TEST.txt",
                operation="upsert",
                mode="100644",
                content="codex-android smoke ok\n",
                diff="@@ -0,0 +1 @@\n+codex-android smoke ok",
            ),
        ),
    )


def test_rehydration_smoke_resolves_remote_session_and_revalidates_artifact():
    resolver = Resolver(owned_session())
    collector = Collector(expected_changes())

    result = run_rehydration_smoke(
        session_id="sess_existing",
        session_resolver=resolver,
        change_collector=collector,
        expected_base_sha="abc123",
    )

    assert result.session_id == "sess_existing"
    assert result.turn_id == "turn_existing"
    assert result.files_changed == 1
    assert result.changed_paths == ("SMOKE_TEST.txt",)
    assert resolver.calls == ["sess_existing"]
    assert collector.calls == [owned_session()]


def test_rehydration_smoke_rejects_foreign_or_missing_session():
    resolver = Resolver(None)
    collector = Collector(expected_changes())

    try:
        run_rehydration_smoke(
            session_id="foreign_session",
            session_resolver=resolver,
            change_collector=collector,
            expected_base_sha="abc123",
        )
        raise AssertionError("Expected RuntimeError")
    except RuntimeError as error:
        assert "owned" in str(error).lower()

    assert collector.calls == []


def test_rehydration_smoke_rejects_unexpected_base_sha():
    resolver = Resolver(owned_session())
    collector = Collector(expected_changes())

    try:
        run_rehydration_smoke(
            session_id="sess_existing",
            session_resolver=resolver,
            change_collector=collector,
            expected_base_sha="different",
        )
        raise AssertionError("Expected RuntimeError")
    except RuntimeError as error:
        assert "base sha" in str(error).lower()
