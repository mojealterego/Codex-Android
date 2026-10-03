from __future__ import annotations

import time
from typing import Any

from sqlalchemy import (
    BigInteger,
    Column,
    MetaData,
    String,
    Table,
    create_engine,
    delete,
    insert,
    select,
)
from sqlalchemy.engine import Engine, RowMapping
from sqlalchemy.exc import IntegrityError

from .agent_service import AgentSessionView


class DatabaseSessionStore:
    CLAIM_TTL_SECONDS = 15 * 60

    def __init__(self, database_url: str) -> None:
        clean_url = self._normalize_url(database_url)
        if not clean_url:
            raise ValueError("Database URL is required")

        connect_args: dict[str, Any] = {}
        if clean_url.startswith("sqlite"):
            connect_args["check_same_thread"] = False

        self._engine: Engine = create_engine(
            clean_url,
            pool_pre_ping=True,
            connect_args=connect_args,
        )
        self._metadata = MetaData()
        self._claims = Table(
            "idempotency_claims",
            self._metadata,
            Column("idempotency_key", String(512), primary_key=True),
            Column("claimed_at", BigInteger, nullable=False),
        )
        self._sessions = Table(
            "agent_sessions",
            self._metadata,
            Column("idempotency_key", String(512), primary_key=True),
            Column("session_id", String(512), nullable=False, unique=True),
            Column("state", String(128), nullable=False),
            Column("environment_id", String(512), nullable=True),
            Column("repository", String(512), nullable=False),
            Column("base_branch", String(512), nullable=False),
            Column("base_sha", String(128), nullable=False),
            Column("events_path", String(1024), nullable=False),
        )
        self._metadata.create_all(self._engine)

    def get(self, key: str) -> AgentSessionView | None:
        clean_key = key.strip()
        if not clean_key:
            return None

        with self._engine.connect() as connection:
            row = connection.execute(
                select(self._sessions).where(
                    self._sessions.c.idempotency_key == clean_key
                )
            ).mappings().first()
        return self._row_to_view(row)

    def get_by_session_id(self, session_id: str) -> AgentSessionView | None:
        clean_session_id = session_id.strip()
        if not clean_session_id:
            return None

        with self._engine.connect() as connection:
            row = connection.execute(
                select(self._sessions).where(
                    self._sessions.c.session_id == clean_session_id
                )
            ).mappings().first()
        return self._row_to_view(row)

    def try_claim(self, key: str) -> bool:
        clean_key = key.strip()
        if not clean_key:
            raise ValueError("Idempotency-Key is required")

        now = int(time.time())
        stale_before = now - self.CLAIM_TTL_SECONDS

        try:
            with self._engine.begin() as connection:
                connection.execute(
                    delete(self._claims).where(
                        self._claims.c.claimed_at < stale_before
                    )
                )

                existing = connection.execute(
                    select(self._sessions.c.idempotency_key).where(
                        self._sessions.c.idempotency_key == clean_key
                    )
                ).first()
                if existing is not None:
                    return False

                connection.execute(
                    insert(self._claims).values(
                        idempotency_key=clean_key,
                        claimed_at=now,
                    )
                )
            return True
        except IntegrityError:
            return False

    def release_claim(self, key: str) -> None:
        clean_key = key.strip()
        if not clean_key:
            return

        with self._engine.begin() as connection:
            connection.execute(
                delete(self._claims).where(
                    self._claims.c.idempotency_key == clean_key
                )
            )

    def put_if_absent(
        self,
        key: str,
        value: AgentSessionView,
    ) -> AgentSessionView:
        clean_key = key.strip()
        if not clean_key:
            raise ValueError("Idempotency-Key is required")
        if not value.session_id.strip():
            raise ValueError("Session id is required")

        try:
            with self._engine.begin() as connection:
                existing = connection.execute(
                    select(self._sessions).where(
                        self._sessions.c.idempotency_key == clean_key
                    )
                ).mappings().first()
                if existing is not None:
                    return self._row_to_view(existing)

                connection.execute(
                    insert(self._sessions).values(
                        idempotency_key=clean_key,
                        session_id=value.session_id,
                        state=value.state,
                        environment_id=value.environment_id,
                        repository=value.repository,
                        base_branch=value.base_branch,
                        base_sha=value.base_sha,
                        events_path=value.events_path,
                    )
                )
            return value
        except IntegrityError as error:
            existing = self.get(clean_key)
            if existing is not None:
                return existing
            raise ValueError(
                "Agent session id is already associated with another request"
            ) from error

    @staticmethod
    def _normalize_url(value: str) -> str:
        clean = value.strip()
        if clean.startswith("postgres://"):
            return "postgresql+psycopg://" + clean[len("postgres://"):]
        if clean.startswith("postgresql://"):
            return "postgresql+psycopg://" + clean[len("postgresql://"):]
        return clean

    @staticmethod
    def _row_to_view(
        row: RowMapping | None,
    ) -> AgentSessionView | None:
        if row is None:
            return None
        return AgentSessionView(
            session_id=str(row["session_id"]),
            state=str(row["state"]),
            environment_id=(
                str(row["environment_id"])
                if row["environment_id"] is not None
                else None
            ),
            repository=str(row["repository"]),
            base_branch=str(row["base_branch"]),
            base_sha=str(row["base_sha"]),
            events_path=str(row["events_path"]),
        )
