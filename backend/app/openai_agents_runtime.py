from __future__ import annotations

from typing import Any

from .agent_service import AgentTask, RemoteAgentSession


class OpenAIAgentsRuntime:
    def __init__(
        self,
        client: Any,
        instructions: str,
    ) -> None:
        self._client = client
        self._instructions = instructions.strip()
        if not self._instructions:
            raise ValueError("Agent instructions are required")

    def create_session(self, task: AgentTask) -> RemoteAgentSession:
        session = self._client.beta.agents.sessions.create(
            agent={
                "model": task.model,
                "instructions": self._instructions,
            },
            environment={
                "type": "openai_hosted",
                "network": {"access": "disabled"},
            },
            metadata={
                "repository": task.repo_full_name,
                "base_branch": task.base_branch,
                "base_sha": task.base_sha,
            },
        )

        environment = getattr(session, "environment", None)
        environment_id = self._environment_id(environment)

        return RemoteAgentSession(
            session_id=str(session.id),
            state=str(session.status),
            environment_id=environment_id,
        )

    @staticmethod
    def _environment_id(environment: Any) -> str | None:
        if environment is None:
            return None

        if isinstance(environment, dict):
            value = environment.get("id")
            return str(value) if value is not None else None

        value = getattr(environment, "id", None)
        return str(value) if value is not None else None
