from __future__ import annotations

import os
from typing import Any

import httpx
from fastapi import FastAPI
from openai import OpenAI

from .agent_changes import OpenAIChangeSetCollector
from .agent_service import AgentService, InMemoryIdempotencyStore
from .github_workspace_preparer import GithubArchiveWorkspacePreparer
from .main import create_app
from .openai_agent_control import OpenAIAgentControl
from .openai_agents_runtime import OpenAIAgentsRuntime
from .openai_event_source import OpenAIAgentEventSource


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
        idempotency_store=InMemoryIdempotencyStore(),
        workspace_preparer=workspace_preparer,
        change_collector=OpenAIChangeSetCollector(openai_client),
        agent_control=OpenAIAgentControl(openai_client),
    )
    return create_app(
        service=service,
        event_source=event_source,
    )


def create_runtime_app() -> FastAPI:
    api_key = os.environ.get("OPENAI_API_KEY", "").strip()
    if not api_key:
        raise RuntimeError("OPENAI_API_KEY is required")

    github_token = os.environ.get("GITHUB_TOKEN")
    instructions = (
        os.environ.get("CODEX_AGENT_INSTRUCTIONS")
        or DEFAULT_AGENT_INSTRUCTIONS
    ).strip()
    if not instructions:
        raise RuntimeError("CODEX_AGENT_INSTRUCTIONS must not be empty")

    openai_client = OpenAI(api_key=api_key)
    http_client = httpx.Client(
        timeout=httpx.Timeout(60.0, connect=10.0),
    )

    app = build_runtime_app(
        openai_client=openai_client,
        http_client=http_client,
        github_token=github_token,
        instructions=instructions,
    )

    @app.on_event("shutdown")
    def close_http_client() -> None:
        http_client.close()

    return app
