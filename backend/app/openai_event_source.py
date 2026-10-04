from __future__ import annotations

import json
from typing import Any, Iterable, Mapping


class OpenAIAgentEventSource:
    def __init__(self, client: Any) -> None:
        self._client = client

    def stream(self, session_id: str) -> Iterable[Mapping[str, object]]:
        if not session_id.strip():
            raise ValueError("Session id is required")

        with self._client.beta.agents.sessions.events.stream(session_id) as events:
            for event in events:
                yield self._event_to_mapping(event)

    @staticmethod
    def _event_to_mapping(event: Any) -> Mapping[str, object]:
        if hasattr(event, "model_dump"):
            payload = event.model_dump(mode="json")
            if isinstance(payload, dict):
                return payload

        if isinstance(event, dict):
            return event

        if hasattr(event, "to_json"):
            payload = json.loads(event.to_json(indent=None))
            if isinstance(payload, dict):
                return payload

        raise TypeError(
            "Unsupported OpenAI agent event type: "
            + type(event).__name__
        )
