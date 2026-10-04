from threading import Event, Thread

from app.agent_service import (
    AgentService,
    AgentTask,
    IdempotencyInProgressError,
    InMemoryIdempotencyStore,
    RemoteAgentSession,
    WorkspaceSeed,
)


class Preparer:
    def prepare(self, task):
        return WorkspaceSeed("file", "sha", 1)


class BlockingRuntime:
    def __init__(self):
        self.entered = Event()
        self.release = Event()
        self.calls = 0

    def create_session(self, task, workspace_seed):
        self.calls += 1
        self.entered.set()
        assert self.release.wait(timeout=5)
        return RemoteAgentSession("sess_once", "in_progress", "env")


def task():
    return AgentTask(
        repo_full_name="owner/repo",
        base_branch="codex/idempotency",
        base_sha="abc123",
        task="Do work",
        model="gpt-6-astra",
    )


def test_concurrent_same_key_creates_only_one_remote_session():
    runtime = BlockingRuntime()
    service = AgentService(
        runtime=runtime,
        idempotency_store=InMemoryIdempotencyStore(),
        workspace_preparer=Preparer(),
    )
    first_result = []
    first_error = []

    def run_first():
        try:
            first_result.append(
                service.start(task(), idempotency_key="same-key")
            )
        except Exception as error:
            first_error.append(error)

    thread = Thread(target=run_first)
    thread.start()
    assert runtime.entered.wait(timeout=5)

    try:
        service.start(task(), idempotency_key="same-key")
        raise AssertionError("Expected IdempotencyInProgressError")
    except IdempotencyInProgressError:
        pass

    assert runtime.calls == 1

    runtime.release.set()
    thread.join(timeout=5)

    assert not first_error
    assert first_result[0].session_id == "sess_once"

    retry = service.start(task(), idempotency_key="same-key")
    assert retry.session_id == "sess_once"
    assert runtime.calls == 1


class FailOnceRuntime:
    def __init__(self):
        self.calls = 0

    def create_session(self, task, workspace_seed):
        self.calls += 1
        if self.calls == 1:
            raise RuntimeError("transient")
        return RemoteAgentSession("sess_retry", "in_progress", "env")


def test_failed_creation_releases_idempotency_claim_for_retry():
    runtime = FailOnceRuntime()
    service = AgentService(
        runtime=runtime,
        idempotency_store=InMemoryIdempotencyStore(),
        workspace_preparer=Preparer(),
    )

    try:
        service.start(task(), idempotency_key="retry-key")
        raise AssertionError("Expected RuntimeError")
    except RuntimeError:
        pass

    result = service.start(task(), idempotency_key="retry-key")

    assert result.session_id == "sess_retry"
    assert runtime.calls == 2
