from __future__ import annotations

import json
from dataclasses import dataclass
from typing import Any, Mapping


MAX_RECOVERY_ITEMS = 500


@dataclass(frozen=True)
class AgentRecoverySnapshot:
    session_id: str
    status: str
    error: str | None
    required_actions: tuple[Mapping[str, object], ...]
    items: tuple[Mapping[str, object], ...]


class OpenAIAgentRecovery:
    def __init__(self, client: Any) -> None:
        self._client = client

    def recover(self, session_id: str) -> AgentRecoverySnapshot:
        clean_session_id = session_id.strip()
        if not clean_session_id:
            raise ValueError("Session id is required")

        remote = self._client.beta.agents.sessions.retrieve(clean_session_id)
        remote_id = str(getattr(remote, "id", "")).strip()
        if not remote_id:
            raise ValueError("OpenAI session retrieve returned an empty id")
        if remote_id != clean_session_id:
            raise ValueError("OpenAI session id mismatch")

        required_actions = tuple(
            self._to_mapping(action)
            for action in (getattr(remote, "required_actions", None) or [])
        )
        items = tuple(self._collect_items(clean_session_id))

        raw_error = getattr(remote, "error", None)
        error = str(raw_error) if raw_error is not None else None

        return AgentRecoverySnapshot(
            session_id=clean_session_id,
            status=str(getattr(remote, "status", "unknown")),
            error=error,
            required_actions=required_actions,
            items=items,
        )

    def _collect_items(
        self,
        session_id: str,
    ) -> list[Mapping[str, object]]:
        page = self._client.beta.agents.sessions.items.list(
            session_id,
            order="asc",
            limit=100,
        )
        result: list[Mapping[str, object]] = []

        while True:
            for item in page:
                result.append(self._to_mapping(item))
                if len(result) >= MAX_RECOVERY_ITEMS:
                    return result

            has_next_page = getattr(page, "has_next_page", None)
            get_next_page = getattr(page, "get_next_page", None)
            if (
                not callable(has_next_page)
                or not callable(get_next_page)
                or not has_next_page()
            ):
                return result

            page = get_next_page()

    @staticmethod
    def _to_mapping(value: Any) -> Mapping[str, object]:
        if isinstance(value, dict):
            return value

        if hasattr(value, "model_dump"):
            payload = value.model_dump(mode="json")
            if isinstance(payload, dict):
                return payload

        if hasattr(value, "to_json"):
            payload = json.loads(value.to_json(indent=None))
            if isinstance(payload, dict):
                return payload

        raise TypeError(
            "Unsupported OpenAI recovery object type: "
            + type(value).__name__
        )
