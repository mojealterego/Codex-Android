from __future__ import annotations

import os
from typing import Any

import httpx
from fastapi import FastAPI
from openai import OpenAI

from .agent_changes import OpenAIChangeSetCollector
from .agent_recovery import OpenAIAgentRecovery
from .agent_service import AgentService, InMemoryIdempotencyStore
from .database_session_store import DatabaseSessionStore
from .github_workspace_preparer import GithubArchiveWorkspacePreparer
from .main import create_app
from .openai_agent_control import OpenAIAgentControl
from .openai_agents_runtime import OpenAIAgentsRuntime
from .openai_event_source import OpenAIAgentEventSource
from .sqlite_session_store import SqliteSessionStore


DEFAULT_AGENT_INSTRUCTIONS = """
Work only inside /workspace/repository.
Treat the repository snapshot as pinned to the supplied base SHA.
Inspect existing tests and project conventions before changing code.
Run the relevant tests and build commands before reporting completion.
Never request, expose, print, or persist GitHub credentials.
Do not use network access from the agent sandbox.
""".strip()


def build_runtime_app(
    *,
    openai_client: Any,
    http_client: Any,
    github_token: str | None,
    instructions: str = DEFAULT_AGENT_INSTRUCTIONS,
    session_store: Any | None = None,
    access_token: str | None = None,
) -> FastAPI:
    workspace_preparer = GithubArchiveWorkspacePreparer(
        http_client=http_client,
        openai_client=openai_client,
        github_token=github_token,
    )
    runtime = OpenAIAgentsRuntime(
        client=openai_client,
        instructions=instructions,
    )
    event_source = OpenAIAgentEventSource(openai_client)
    service = AgentService(
        runtime=runtime,
        idempotency_store=(
            session_store
            if session_store is not None
            else InMemoryIdempotencyStore()
        ),
        workspace_preparer=workspace_preparer,
        change_collector=OpenAIChangeSetCollector(openai_client),
        agent_control=OpenAIAgentControl(openai_client),
        recovery_source=OpenAIAgentRecovery(openai_client),
    )
    return create_app(
        service=service,
        event_source=event_source,
        access_token=access_token,
    )



def create_session_store(
    *,
    database_url: str | None,
    sqlite_path: str,
) -> Any:
    clean_database_url = (database_url or "").strip()
    if clean_database_url:
        return DatabaseSessionStore(clean_database_url)

    clean_sqlite_path = sqlite_path.strip()
    if not clean_sqlite_path:
        raise RuntimeError("CODEX_STATE_DB must not be empty")
    return SqliteSessionStore(clean_sqlite_path)


def create_runtime_app() -> FastAPI:
    api_key = os.environ.get("OPENAI_API_KEY", "").strip()
    if not api_key:
        raise RuntimeError("OPENAI_API_KEY is required")

    github_token = os.environ.get("GITHUB_TOKEN")
    bff_token = os.environ.get("CODEX_BFF_TOKEN", "").strip()
    if not bff_token:
        raise RuntimeError("CODEX_BFF_TOKEN is required")

    instructions = (
        os.environ.get("CODEX_AGENT_INSTRUCTIONS")
        or DEFAULT_AGENT_INSTRUCTIONS
    ).strip()
    if not instructions:
        raise RuntimeError("CODEX_AGENT_INSTRUCTIONS must not be empty")

    state_database = (
        os.environ.get("CODEX_STATE_DB")
        or "./codex-android-state.sqlite3"
    ).strip()
    database_url = os.environ.get("DATABASE_URL")

    openai_client = OpenAI(api_key=api_key)
    http_client = httpx.Client(
        timeout=httpx.Timeout(60.0, connect=10.0),
    )

    app = build_runtime_app(
        openai_client=openai_client,
        http_client=http_client,
        github_token=github_token,
        instructions=instructions,
        session_store=create_session_store(
            database_url=database_url,
            sqlite_path=state_database,
        ),
        access_token=bff_token,
    )

    @app.on_event("shutdown")
    def close_http_client() -> None:
        http_client.close()

    return app
