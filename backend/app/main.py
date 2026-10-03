from __future__ import annotations

import json
from typing import Iterable, Mapping, Protocol

from fastapi import FastAPI, Header, HTTPException, status
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, Field

from .agent_service import AgentService, AgentTask


class AgentEventSource(Protocol):
    def stream(self, session_id: str) -> Iterable[Mapping[str, object]]:
        ...


class StartAgentSessionRequest(BaseModel):
    repository: str = Field(min_length=3, max_length=512)
    base_branch: str = Field(min_length=1, max_length=255)
    base_sha: str = Field(min_length=1, max_length=128)
    task: str = Field(min_length=1, max_length=20000)
    model: str = Field(min_length=1, max_length=128)


class AgentSessionResponse(BaseModel):
    session_id: str
    state: str
    environment_id: str | None
    repository: str
    base_branch: str
    base_sha: str
    events_path: str


def create_app(
    service: AgentService,
    event_source: AgentEventSource,
) -> FastAPI:
    app = FastAPI(
        title="Codex-Android BFF",
        version="0.1.0",
    )

    @app.get("/healthz")
    def healthz() -> dict[str, str]:
        return {"status": "ok"}

    @app.post(
        "/v1/agents/sessions",
        response_model=AgentSessionResponse,
        status_code=status.HTTP_201_CREATED,
    )
    def create_session(
        request: StartAgentSessionRequest,
        idempotency_key: str = Header(..., alias="Idempotency-Key"),
    ) -> AgentSessionResponse:
        try:
            session = service.start(
                AgentTask(
                    repo_full_name=request.repository,
                    base_branch=request.base_branch,
                    base_sha=request.base_sha,
                    task=request.task,
                    model=request.model,
                ),
                idempotency_key=idempotency_key,
            )
        except ValueError as error:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail=str(error),
            ) from error

        return AgentSessionResponse(
            session_id=session.session_id,
            state=session.state,
            environment_id=session.environment_id,
            repository=session.repository,
            base_branch=session.base_branch,
            base_sha=session.base_sha,
            events_path=session.events_path,
        )

    @app.get("/v1/agents/sessions/{session_id}/events")
    def stream_events(session_id: str) -> StreamingResponse:
        def generate():
            for raw_event in event_source.stream(session_id):
                event = dict(raw_event)
                event_type = str(event.get("type") or "message")
                payload = json.dumps(
                    event,
                    separators=(",", ":"),
                    ensure_ascii=False,
                )
                yield f"event: {event_type}\ndata: {payload}\n\n"

        return StreamingResponse(
            generate(),
            media_type="text/event-stream",
            headers={
                "Cache-Control": "no-cache",
                "X-Accel-Buffering": "no",
            },
        )

    return app
