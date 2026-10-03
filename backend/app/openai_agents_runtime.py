from __future__ import annotations

from typing import Any

from .agent_service import AgentTask, RemoteAgentSession, WorkspaceSeed


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

    def create_session(
        self,
        task: AgentTask,
        workspace_seed: WorkspaceSeed | None = None,
    ) -> RemoteAgentSession:
        environment: dict[str, object] = {
            "type": "openai_hosted",
            "network": {"access": "disabled"},
        }

        request: dict[str, object] = {
            "agent": {
                "model": task.model,
                "instructions": self._instructions,
            },
            "environment": environment,
            "metadata": {
                "repository": task.repo_full_name,
                "base_branch": task.base_branch,
                "base_sha": task.base_sha,
            },
        }

        if workspace_seed is not None:
            workspace_seed.validate()
            environment["files"] = [{
                "type": "file_id",
                "file_id": workspace_seed.file_id,
                "path": workspace_seed.archive_path,
            }]
            environment["setup_commands"] = [{
                "command": (
                    f"mkdir -p {workspace_seed.workspace_path} && "
                    f"tar -xzf {workspace_seed.archive_path} "
                    f"-C {workspace_seed.workspace_path} --strip-components=1"
                )
            }]
            request["input"] = self._initial_input(task, workspace_seed)

        session = self._client.beta.agents.sessions.create(**request)

        environment_value = getattr(session, "environment", None)
        environment_id = self._environment_id(environment_value)

        return RemoteAgentSession(
            session_id=str(session.id),
            state=str(session.status),
            environment_id=environment_id,
        )

    @staticmethod
    def _initial_input(task: AgentTask, workspace_seed: WorkspaceSeed) -> str:
        return (
            f"Repository: {task.repo_full_name}\n"
            f"Pinned base branch: {task.base_branch}\n"
            f"Pinned base SHA: {task.base_sha}\n"
            f"Workspace: {workspace_seed.workspace_path}\n\n"
            f"Task:\n{task.task}"
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
