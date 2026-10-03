from __future__ import annotations

import os
import time
import uuid
from dataclasses import dataclass
from typing import Any

import httpx
from openai import OpenAI

from .agent_changes import AgentChangeSet, OpenAIChangeSetCollector
from .agent_recovery import OpenAIAgentRecovery
from .agent_service import (
    AgentService,
    AgentTask,
    InMemoryIdempotencyStore,
)
from .github_workspace_preparer import GithubArchiveWorkspacePreparer
from .openai_agents_runtime import OpenAIAgentsRuntime


EXPECTED_SMOKE_PATH = "SMOKE_TEST.txt"
EXPECTED_SMOKE_CONTENT = "codex-android smoke ok\n"

SMOKE_INSTRUCTIONS = """
Work only inside /workspace/repository.
Treat the repository snapshot as pinned to the supplied base SHA.
Do not access the network, push to GitHub, publish changes, or request credentials.
Make only the explicitly requested smoke-test change.
Immediately before finishing, run the required change exporter command from the task.
""".strip()


@dataclass(frozen=True)
class RuntimeSmokeResult:
    session_id: str
    turn_id: str
    files_changed: int
    changed_paths: tuple[str, ...]


def run_runtime_smoke(
    *,
    service: AgentService,
    task: AgentTask,
    idempotency_key: str,
    timeout_seconds: float = 300.0,
    poll_interval_seconds: float = 2.0,
) -> RuntimeSmokeResult:
    if timeout_seconds <= 0:
        raise ValueError("Smoke timeout must be positive")
    if poll_interval_seconds < 0:
        raise ValueError("Smoke poll interval must not be negative")

    session = service.start(task, idempotency_key=idempotency_key)
    deadline = time.monotonic() + timeout_seconds
    last_collection_error: Exception | None = None

    while time.monotonic() < deadline:
        recovery = service.recover(session.session_id)
        status = str(getattr(recovery, "status", "")).strip().lower()
        error = getattr(recovery, "error", None)

        if status in {"failed", "cancelled", "expired"}:
            detail = str(error or status)
            raise RuntimeError(f"Agent smoke session failed: {detail}")

        if status == "idle":
            try:
                change_set = service.collect_changes(session.session_id)
            except ValueError as collection_error:
                last_collection_error = collection_error
            else:
                return _validate_smoke_change_set(change_set)

        time.sleep(poll_interval_seconds)

    suffix = (
        f": {last_collection_error}"
        if last_collection_error is not None
        else ""
    )
    raise TimeoutError(
        "Timed out waiting for completed smoke change Artifact" + suffix
    )


def _validate_smoke_change_set(
    change_set: AgentChangeSet,
) -> RuntimeSmokeResult:
    if len(change_set.files) != 1:
        raise RuntimeError(
            "Smoke agent must produce exactly one changed file"
        )

    change = change_set.files[0]
    if change.path != EXPECTED_SMOKE_PATH:
        raise RuntimeError(
            "Smoke agent changed an unexpected path: " + change.path
        )
    if change.operation != "upsert":
        raise RuntimeError(
            "Smoke agent must upsert the expected smoke file"
        )
    if change.content != EXPECTED_SMOKE_CONTENT:
        raise RuntimeError(
            "Smoke agent produced unexpected smoke file content"
        )

    return RuntimeSmokeResult(
        session_id=change_set.session_id,
        turn_id=change_set.turn_id,
        files_changed=len(change_set.files),
        changed_paths=tuple(item.path for item in change_set.files),
    )


def _build_service(
    *,
    openai_client: Any,
    http_client: Any,
    github_token: str | None,
) -> AgentService:
    return AgentService(
        runtime=OpenAIAgentsRuntime(
            client=openai_client,
            instructions=SMOKE_INSTRUCTIONS,
        ),
        idempotency_store=InMemoryIdempotencyStore(),
        workspace_preparer=GithubArchiveWorkspacePreparer(
            http_client=http_client,
            openai_client=openai_client,
            github_token=github_token,
        ),
        change_collector=OpenAIChangeSetCollector(openai_client),
        recovery_source=OpenAIAgentRecovery(openai_client),
    )


def main() -> int:
    api_key = os.environ.get("OPENAI_API_KEY", "").strip()
    if not api_key:
        raise RuntimeError("OPENAI_API_KEY is required")

    repo = (
        os.environ.get("CODEX_SMOKE_REPO")
        or "mojealterego/Codex-Android"
    ).strip()
    branch = (
        os.environ.get("CODEX_SMOKE_BRANCH")
        or "codex/agent-runtime-p2"
    ).strip()
    base_sha = os.environ.get("CODEX_SMOKE_SHA", "").strip()
    if not base_sha:
        raise RuntimeError("CODEX_SMOKE_SHA is required")

    model = (
        os.environ.get("CODEX_SMOKE_MODEL")
        or "gpt-5.6-sol"
    ).strip()
    timeout_seconds = float(
        os.environ.get("CODEX_SMOKE_TIMEOUT_SECONDS", "300")
    )

    task = AgentTask(
        repo_full_name=repo,
        base_branch=branch,
        base_sha=base_sha,
        task=(
            "Create or overwrite SMOKE_TEST.txt with exactly this UTF-8 "
            "text including the final newline: "
            "'codex-android smoke ok\\n'. "
            "Do not modify any other repository file. "
            "Run the required change exporter immediately before finishing."
        ),
        model=model,
    )

    openai_client = OpenAI(api_key=api_key)
    http_client = httpx.Client(
        timeout=httpx.Timeout(60.0, connect=10.0),
    )
    try:
        service = _build_service(
            openai_client=openai_client,
            http_client=http_client,
            github_token=os.environ.get("GITHUB_TOKEN"),
        )
        result = run_runtime_smoke(
            service=service,
            task=task,
            idempotency_key=(
                os.environ.get("CODEX_SMOKE_IDEMPOTENCY_KEY")
                or f"render-smoke-{uuid.uuid4()}"
            ),
            timeout_seconds=timeout_seconds,
        )
    finally:
        http_client.close()

    print(
        "RUNTIME_SMOKE_SUCCESS "
        f"session={result.session_id} "
        f"turn={result.turn_id} "
        f"files={result.files_changed} "
        f"paths={','.join(result.changed_paths)}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
