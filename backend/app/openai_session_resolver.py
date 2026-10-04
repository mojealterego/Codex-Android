from __future__ import annotations

from collections.abc import Mapping
from typing import Any

from .agent_service import AgentSessionView


CODEX_ANDROID_SCHEMA = "1"


class OpenAIAgentSessionResolver:
    def __init__(self, client: Any) -> None:
        self._client = client

    def resolve(self, session_id: str) -> AgentSessionView | None:
        clean_session_id = session_id.strip()
        if not clean_session_id:
            raise ValueError("Session id is required")

        remote = self._client.beta.agents.sessions.retrieve(clean_session_id)
        remote_id = str(getattr(remote, "id", "")).strip()
        if not remote_id:
            raise ValueError("OpenAI session retrieve returned an empty id")
        if remote_id != clean_session_id:
            raise ValueError("OpenAI session id mismatch")

        metadata = getattr(remote, "metadata", None)
        if not isinstance(metadata, Mapping):
            return None
        if str(metadata.get("codex_android_schema") or "") != CODEX_ANDROID_SCHEMA:
            return None

        repository = str(metadata.get("repository") or "").strip()
        base_branch = str(metadata.get("base_branch") or "").strip()
        base_sha = str(metadata.get("base_sha") or "").strip()

        repo_parts = repository.strip("/").split("/")
        if len(repo_parts) != 2 or not all(part.strip() for part in repo_parts):
            return None
        if (
            not base_branch.startswith("codex/")
            or not base_branch.removeprefix("codex/").strip()
        ):
            return None
        if not base_sha:
            return None

        environment = getattr(remote, "environment", None)
        environment_id = self._environment_id(environment)

        return AgentSessionView(
            session_id=clean_session_id,
            state=str(getattr(remote, "status", "unknown")),
            environment_id=environment_id,
            repository=repository,
            base_branch=base_branch,
            base_sha=base_sha,
            events_path=f"/v1/agents/sessions/{clean_session_id}/events",
        )

    @staticmethod
    def _environment_id(environment: Any) -> str | None:
        if environment is None:
            return None
        if isinstance(environment, Mapping):
            value = environment.get("id")
            return str(value) if value is not None else None
        value = getattr(environment, "id", None)
        return str(value) if value is not None else None
