from __future__ import annotations

import json
import secrets
from typing import Iterable, Mapping, Protocol

from fastapi import FastAPI, Header, HTTPException, Request, status
from fastapi.responses import JSONResponse, StreamingResponse
from pydantic import BaseModel, Field

from .agent_changes import AgentChangeSet
from .agent_recovery import AgentRecoverySnapshot
from .agent_service import AgentService, AgentTask, IdempotencyInProgressError


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


class AgentSteerRequest(BaseModel):
    message: str = Field(min_length=1, max_length=20000)


class AgentFileChangeResponse(BaseModel):
    path: str
    operation: str
    mode: str
    content: str | None
    diff: str
    rename_from: str | None


class AgentRecoveryResponse(BaseModel):
    session_id: str
    status: str
    error: str | None
    required_actions: list[dict[str, object]]
    items: list[dict[str, object]]


class AgentChangeSetResponse(BaseModel):
    session_id: str
    turn_id: str
    base_branch: str
    base_sha: str
    files: list[AgentFileChangeResponse]


def create_app(
    service: AgentService,
    event_source: AgentEventSource,
    access_token: str | None = None,
) -> FastAPI:
    app = FastAPI(
        title="Codex-Android BFF",
        version="0.4.0",
    )

    configured_token = access_token.strip() if access_token is not None else None
    if access_token is not None and not configured_token:
        raise ValueError("BFF access token must not be blank")

    @app.middleware("http")
    async def protect_v1(request: Request, call_next):
        if request.url.path.startswith("/v1/") and configured_token is not None:
            authorization = request.headers.get("Authorization", "")
            prefix = "Bearer "
            provided = (
                authorization[len(prefix):]
                if authorization.startswith(prefix)
                else ""
            )
            if not provided or not secrets.compare_digest(
                provided,
                configured_token,
            ):
                return JSONResponse(
                    status_code=status.HTTP_401_UNAUTHORIZED,
                    content={"detail": "Unauthorized"},
                    headers={"WWW-Authenticate": "Bearer"},
                )
        return await call_next(request)

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
        except IdempotencyInProgressError as error:
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail=str(error),
            ) from error
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

    @app.post(
        "/v1/agents/sessions/{session_id}/messages",
        status_code=status.HTTP_202_ACCEPTED,
    )
    def steer_session(
        session_id: str,
        request: AgentSteerRequest,
        idempotency_key: str = Header(..., alias="Idempotency-Key"),
    ) -> dict[str, str]:
        try:
            service.steer(
                session_id,
                request.message,
                idempotency_key=idempotency_key,
            )
        except ValueError as error:
            message = str(error)
            code = (
                status.HTTP_404_NOT_FOUND
                if "Unknown agent session" in message
                else status.HTTP_400_BAD_REQUEST
            )
            raise HTTPException(status_code=code, detail=message) from error
        return {"status": "accepted"}

    @app.post(
        "/v1/agents/sessions/{session_id}/cancel",
        status_code=status.HTTP_202_ACCEPTED,
    )
    def cancel_session(session_id: str) -> dict[str, str]:
        try:
            service.cancel(session_id)
        except ValueError as error:
            message = str(error)
            code = (
                status.HTTP_404_NOT_FOUND
                if "Unknown agent session" in message
                else status.HTTP_400_BAD_REQUEST
            )
            raise HTTPException(status_code=code, detail=message) from error
        return {"status": "accepted"}

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

    @app.get(
        "/v1/agents/sessions/{session_id}/recovery",
        response_model=AgentRecoveryResponse,
    )
    def recover_session(session_id: str) -> AgentRecoveryResponse:
        try:
            recovery = service.recover(session_id)
        except ValueError as error:
            message = str(error)
            code = (
                status.HTTP_404_NOT_FOUND
                if "Unknown agent session" in message
                else status.HTTP_409_CONFLICT
            )
            raise HTTPException(status_code=code, detail=message) from error

        if not isinstance(recovery, AgentRecoverySnapshot):
            raise HTTPException(
                status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
                detail="Agent recovery source returned an invalid result",
            )

        return AgentRecoveryResponse(
            session_id=recovery.session_id,
            status=recovery.status,
            error=recovery.error,
            required_actions=[
                dict(item) for item in recovery.required_actions
            ],
            items=[dict(item) for item in recovery.items],
        )

    @app.get(
        "/v1/agents/sessions/{session_id}/changes",
        response_model=AgentChangeSetResponse,
    )
    def collect_changes(session_id: str) -> AgentChangeSetResponse:
        try:
            change_set = service.collect_changes(session_id)
        except ValueError as error:
            message = str(error)
            code = (
                status.HTTP_404_NOT_FOUND
                if "Unknown agent session" in message
                else status.HTTP_409_CONFLICT
            )
            raise HTTPException(status_code=code, detail=message) from error

        if not isinstance(change_set, AgentChangeSet):
            raise HTTPException(
                status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
                detail="Agent change collector returned an invalid result",
            )

        return AgentChangeSetResponse(
            session_id=change_set.session_id,
            turn_id=change_set.turn_id,
            base_branch=change_set.base_branch,
            base_sha=change_set.base_sha,
            files=[
                AgentFileChangeResponse(
                    path=item.path,
                    operation=item.operation,
                    mode=item.mode,
                    content=item.content,
                    diff=item.diff,
                    rename_from=item.rename_from,
                )
                for item in change_set.files
            ],
        )

    return app
