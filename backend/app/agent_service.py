from __future__ import annotations

from dataclasses import dataclass
from threading import RLock
from typing import Any, Protocol


@dataclass(frozen=True)
class AgentTask:
    repo_full_name: str
    base_branch: str
    base_sha: str
    task: str
    model: str


@dataclass(frozen=True)
class WorkspaceSeed:
    file_id: str
    sha256: str
    size_bytes: int
    archive_path: str = "/workspace/input/repository.tar.gz"
    workspace_path: str = "/workspace/repository"

    def validate(self) -> None:
        if not self.file_id.strip():
            raise ValueError("Workspace file id is required")
        if not self.sha256.strip():
            raise ValueError("Workspace archive SHA-256 is required")
        if self.size_bytes <= 0:
            raise ValueError("Workspace archive size must be positive")
        if not self.archive_path.startswith("/workspace/"):
            raise ValueError("Workspace archive path must be inside /workspace")
        if not self.workspace_path.startswith("/workspace/"):
            raise ValueError("Workspace path must be inside /workspace")


@dataclass(frozen=True)
class RemoteAgentSession:
    session_id: str
    state: str
    environment_id: str | None = None


@dataclass(frozen=True)
class AgentSessionView:
    session_id: str
    state: str
    environment_id: str | None
    repository: str
    base_branch: str
    base_sha: str
    events_path: str


class WorkspacePreparer(Protocol):
    def prepare(self, task: AgentTask) -> WorkspaceSeed:
        ...


class AgentRuntime(Protocol):
    def create_session(
        self,
        task: AgentTask,
        workspace_seed: WorkspaceSeed,
    ) -> RemoteAgentSession:
        ...


class AgentChangeCollector(Protocol):
    def collect(self, session: AgentSessionView) -> Any:
        ...


class AgentRecoverySource(Protocol):
    def recover(self, session_id: str) -> Any:
        ...


class AgentControl(Protocol):
    def steer(
        self,
        session_id: str,
        message: str,
        idempotency_key: str,
    ) -> None:
        ...

    def cancel(self, session_id: str) -> None:
        ...


class IdempotencyStore(Protocol):
    def get(self, key: str) -> AgentSessionView | None:
        ...

    def get_by_session_id(self, session_id: str) -> AgentSessionView | None:
        ...

    def put_if_absent(
        self,
        key: str,
        value: AgentSessionView,
    ) -> AgentSessionView:
        ...


class InMemoryIdempotencyStore:
    def __init__(self) -> None:
        self._lock = RLock()
        self._values: dict[str, AgentSessionView] = {}
        self._sessions: dict[str, AgentSessionView] = {}

    def get(self, key: str) -> AgentSessionView | None:
        with self._lock:
            return self._values.get(key)

    def get_by_session_id(self, session_id: str) -> AgentSessionView | None:
        with self._lock:
            return self._sessions.get(session_id)

    def put_if_absent(
        self,
        key: str,
        value: AgentSessionView,
    ) -> AgentSessionView:
        with self._lock:
            existing = self._values.get(key)
            if existing is not None:
                return existing
            self._values[key] = value
            self._sessions[value.session_id] = value
            return value


class AgentService:
    def __init__(
        self,
        runtime: AgentRuntime,
        idempotency_store: IdempotencyStore,
        workspace_preparer: WorkspacePreparer,
        change_collector: AgentChangeCollector | None = None,
        agent_control: AgentControl | None = None,
        recovery_source: AgentRecoverySource | None = None,
    ) -> None:
        self._runtime = runtime
        self._idempotency_store = idempotency_store
        self._workspace_preparer = workspace_preparer
        self._change_collector = change_collector
        self._agent_control = agent_control
        self._recovery_source = recovery_source

    def start(
        self,
        task: AgentTask,
        idempotency_key: str,
    ) -> AgentSessionView:
        key = idempotency_key.strip()
        if not key:
            raise ValueError("Idempotency-Key is required")

        self._validate_task(task)

        existing = self._idempotency_store.get(key)
        if existing is not None:
            return existing

        workspace_seed = self._workspace_preparer.prepare(task)
        workspace_seed.validate()

        remote = self._runtime.create_session(task, workspace_seed)
        if not remote.session_id.strip():
            raise ValueError("Remote agent session id is empty")

        view = AgentSessionView(
            session_id=remote.session_id,
            state=remote.state,
            environment_id=remote.environment_id,
            repository=task.repo_full_name,
            base_branch=task.base_branch,
            base_sha=task.base_sha,
            events_path=f"/v1/agents/sessions/{remote.session_id}/events",
        )

        return self._idempotency_store.put_if_absent(key, view)

    def collect_changes(self, session_id: str) -> Any:
        session = self._require_session(session_id)
        if self._change_collector is None:
            raise ValueError("Agent change collection is not configured")
        return self._change_collector.collect(session)

    def recover(self, session_id: str) -> Any:
        session = self._require_session(session_id)
        if self._recovery_source is None:
            raise ValueError("Agent recovery is not configured")
        return self._recovery_source.recover(session.session_id)

    def steer(
        self,
        session_id: str,
        message: str,
        idempotency_key: str,
    ) -> None:
        session = self._require_session(session_id)
        if self._agent_control is None:
            raise ValueError("Agent control is not configured")
        self._agent_control.steer(
            session.session_id,
            message,
            idempotency_key=idempotency_key,
        )

    def cancel(self, session_id: str) -> None:
        session = self._require_session(session_id)
        if self._agent_control is None:
            raise ValueError("Agent control is not configured")
        self._agent_control.cancel(session.session_id)

    def _require_session(self, session_id: str) -> AgentSessionView:
        clean_session_id = session_id.strip()
        if not clean_session_id:
            raise ValueError("Session id is required")
        session = self._idempotency_store.get_by_session_id(clean_session_id)
        if session is None:
            raise ValueError("Unknown agent session")
        return session

    @staticmethod
    def _validate_task(task: AgentTask) -> None:
        if "/" not in task.repo_full_name.strip("/"):
            raise ValueError("Repository must use owner/name form")
        if not task.base_branch.strip():
            raise ValueError("Base branch is required")
        if not task.base_sha.strip():
            raise ValueError("Base SHA is required")
        if not task.task.strip():
            raise ValueError("Agent task is required")
        if not task.model.strip():
            raise ValueError("Agent model is required")
