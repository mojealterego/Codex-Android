from app.agent_changes import AgentChangeSet, AgentFileChange
from app.agent_service import (
    AgentService,
    AgentTask,
    InMemoryIdempotencyStore,
    RemoteAgentSession,
    WorkspaceSeed,
)
from app.runtime_smoke import run_runtime_smoke


class Preparer:
    def prepare(self, task):
        return WorkspaceSeed("file", "sha256", 1)


class Runtime:
    def create_session(self, task, workspace_seed):
        return RemoteAgentSession("sess_smoke", "in_progress", "env_smoke")


class Collector:
    def __init__(self):
        self.calls = 0

    def collect(self, session):
        self.calls += 1
        if self.calls < 2:
            raise ValueError(
                "No completed agent turn published /workspace/outputs/changes.json"
            )
        return AgentChangeSet(
            session_id=session.session_id,
            turn_id="turn_smoke",
            base_branch=session.base_branch,
            base_sha=session.base_sha,
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


class Recovery:
    def __init__(self):
        self.calls = 0

    def recover(self, session_id):
        self.calls += 1
        return type(
            "Recovery",
            (),
            {
                "status": "in_progress" if self.calls == 1 else "idle",
                "error": None,
                "required_actions": (),
                "items": (),
            },
        )()


def test_smoke_waits_for_completed_artifact_and_validates_expected_change(monkeypatch):
    collector = Collector()
    service = AgentService(
        runtime=Runtime(),
        idempotency_store=InMemoryIdempotencyStore(),
        workspace_preparer=Preparer(),
        change_collector=collector,
        recovery_source=Recovery(),
    )
    monkeypatch.setattr("app.runtime_smoke.time.sleep", lambda _: None)

    result = run_runtime_smoke(
        service=service,
        task=AgentTask(
            repo_full_name="owner/repo",
            base_branch="codex/smoke",
            base_sha="abc123",
            task="Create the smoke file.",
            model="gpt-5.6-sol",
        ),
        idempotency_key="smoke-key",
        timeout_seconds=5,
        poll_interval_seconds=0,
    )

    assert result.session_id == "sess_smoke"
    assert result.turn_id == "turn_smoke"
    assert result.files_changed == 1
    assert result.changed_paths == ("SMOKE_TEST.txt",)


class FailedRecovery:
    def recover(self, session_id):
        return type(
            "Recovery",
            (),
            {
                "status": "failed",
                "error": "agent failed",
                "required_actions": (),
                "items": (),
            },
        )()


def test_smoke_fails_immediately_on_remote_session_failure(monkeypatch):
    service = AgentService(
        runtime=Runtime(),
        idempotency_store=InMemoryIdempotencyStore(),
        workspace_preparer=Preparer(),
        change_collector=Collector(),
        recovery_source=FailedRecovery(),
    )
    monkeypatch.setattr("app.runtime_smoke.time.sleep", lambda _: None)

    try:
        run_runtime_smoke(
            service=service,
            task=AgentTask(
                repo_full_name="owner/repo",
                base_branch="codex/smoke",
                base_sha="abc123",
                task="Create the smoke file.",
                model="gpt-5.6-sol",
            ),
            idempotency_key="smoke-key",
            timeout_seconds=5,
            poll_interval_seconds=0,
        )
        raise AssertionError("Expected RuntimeError")
    except RuntimeError as error:
        assert "agent failed" in str(error).lower()
