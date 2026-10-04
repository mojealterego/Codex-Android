from __future__ import annotations

import os
from typing import Any, Protocol

from openai import OpenAI

from .agent_changes import OpenAIChangeSetCollector
from .agent_service import AgentSessionView
from .openai_session_resolver import OpenAIAgentSessionResolver
from .runtime_smoke import RuntimeSmokeResult, _validate_smoke_change_set


class SessionResolver(Protocol):
    def resolve(self, session_id: str) -> AgentSessionView | None:
        ...


class ChangeCollector(Protocol):
    def collect(self, session: AgentSessionView) -> Any:
        ...


def run_rehydration_smoke(
    *,
    session_id: str,
    session_resolver: SessionResolver,
    change_collector: ChangeCollector,
    expected_base_sha: str | None = None,
) -> RuntimeSmokeResult:
    clean_session_id = session_id.strip()
    if not clean_session_id:
        raise ValueError("Rehydration smoke session id is required")

    session = session_resolver.resolve(clean_session_id)
    if session is None:
        raise RuntimeError(
            "Rehydration smoke could not resolve an owned Codex-Android session"
        )

    expected_sha = (expected_base_sha or "").strip()
    if expected_sha and session.base_sha != expected_sha:
        raise RuntimeError(
            "Rehydration smoke base SHA does not match the expected session base SHA"
        )

    change_set = change_collector.collect(session)
    return _validate_smoke_change_set(change_set)


def main() -> int:
    api_key = os.environ.get("OPENAI_API_KEY", "").strip()
    if not api_key:
        raise RuntimeError("OPENAI_API_KEY is required")

    session_id = os.environ.get(
        "CODEX_REHYDRATE_SMOKE_SESSION_ID",
        "",
    ).strip()
    if not session_id:
        raise RuntimeError("CODEX_REHYDRATE_SMOKE_SESSION_ID is required")

    client = OpenAI(api_key=api_key)
    result = run_rehydration_smoke(
        session_id=session_id,
        session_resolver=OpenAIAgentSessionResolver(client),
        change_collector=OpenAIChangeSetCollector(client),
        expected_base_sha=os.environ.get(
            "CODEX_REHYDRATE_SMOKE_EXPECTED_SHA"
        ),
    )

    print(
        "REHYDRATION_SMOKE_SUCCESS "
        f"session={result.session_id} "
        f"turn={result.turn_id} "
        f"files={result.files_changed} "
        f"paths={','.join(result.changed_paths)}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
