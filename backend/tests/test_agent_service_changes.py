from app.agent_changes import AgentChangeSet, AgentFileChange
from app.agent_service import (
    AgentService,
    AgentTask,
    InMemoryIdempotencyStore,
    RemoteAgentSession,
    WorkspaceSeed,
)


class FakeWorkspacePreparer:
    def prepare(self, task):
        return WorkspaceSeed(
            file_id="file_seed",
            sha256="seed-sha",
            size_bytes=100,
        )


class FakeRuntime:
    def create_session(self, task, workspace_seed):
        return RemoteAgentSession(
            session_id="sess_changes_123",
            state="in_progress",
            environment_id="env_changes_123",
        )


class FakeCollector:
    def __init__(self):
        self.sessions = []

    def collect(self, session):
        self.sessions.append(session)
        return AgentChangeSet(
            session_id=session.session_id,
            turn_id="turn_123",
            base_branch=session.base_branch,
            base_sha=session.base_sha,
            files=(
                AgentFileChange(
                    path="README.md",
                    operation="upsert",
                    mode="100644",
                    content="updated\n",
                    diff="@@ -1 +1 @@",
                ),
            ),
        )


def test_agent_service_recovers_changes_only_for_known_session():
    collector = FakeCollector()
    service = AgentService(
        runtime=FakeRuntime(),
        idempotency_store=InMemoryIdempotencyStore(),
        workspace_preparer=FakeWorkspacePreparer(),
        change_collector=collector,
    )
    view = service.start(
        AgentTask(
            repo_full_name="owner/repo",
            base_branch="codex/change",
            base_sha="base123",
            task="Update README",
            model="gpt-6-astra",
        ),
        idempotency_key="task-1",
    )

    changes = service.collect_changes(view.session_id)

    assert changes.base_sha == "base123"
    assert changes.files[0].path == "README.md"
    assert collector.sessions == [view]

    try:
        service.collect_changes("sess_unknown")
        raise AssertionError("Expected ValueError")
    except ValueError as error:
        assert "unknown agent session" in str(error).lower()
