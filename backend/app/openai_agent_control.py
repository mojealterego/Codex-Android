from __future__ import annotations

from typing import Any


class OpenAIAgentControl:
    def __init__(self, client: Any) -> None:
        self._client = client

    def steer(
        self,
        session_id: str,
        message: str,
        idempotency_key: str,
    ) -> None:
        clean_session = session_id.strip()
        clean_message = message.strip()
        clean_key = idempotency_key.strip()

        if not clean_session:
            raise ValueError("Session id is required")
        if not clean_message:
            raise ValueError("Steer message is required")
        if not clean_key:
            raise ValueError("Idempotency-Key is required")

        self._client.beta.agents.sessions.events.create(
            clean_session,
            idempotency_key=clean_key,
            events=[{
                "type": "agent.session.input.message",
                "input": [{
                    "role": "user",
                    "content": [{
                        "type": "input_text",
                        "text": clean_message,
                    }],
                }],
            }],
        )

    def cancel(self, session_id: str) -> None:
        clean_session = session_id.strip()
        if not clean_session:
            raise ValueError("Session id is required")

        self._client.beta.agents.sessions.events.create(
            clean_session,
            events=[{
                "type": "agent.session.input.cancel",
            }],
        )
