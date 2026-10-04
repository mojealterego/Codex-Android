from __future__ import annotations

import sqlite3
import time
from pathlib import Path
from threading import RLock

from .agent_service import AgentSessionView


class SqliteSessionStore:
    CLAIM_TTL_SECONDS = 15 * 60

    def __init__(self, database_path: str | Path) -> None:
        self._path = str(database_path)
        if not self._path.strip():
            raise ValueError("Session database path is required")

        if self._path != ":memory:":
            Path(self._path).expanduser().resolve().parent.mkdir(
                parents=True,
                exist_ok=True,
            )

        self._lock = RLock()
        self._initialize()

    def get(self, key: str) -> AgentSessionView | None:
        clean_key = key.strip()
        if not clean_key:
            return None

        with self._connect() as connection:
            row = connection.execute(
                """
                SELECT session_id, state, environment_id, repository,
                       base_branch, base_sha, events_path
                  FROM agent_sessions
                 WHERE idempotency_key = ?
                """,
                (clean_key,),
            ).fetchone()
        return self._row_to_view(row)

    def try_claim(self, key: str) -> bool:
        clean_key = key.strip()
        if not clean_key:
            raise ValueError("Idempotency-Key is required")

        now = int(time.time())
        stale_before = now - self.CLAIM_TTL_SECONDS

        with self._lock:
            with self._connect() as connection:
                connection.execute("BEGIN IMMEDIATE")
                connection.execute(
                    """
                    DELETE FROM idempotency_claims
                     WHERE claimed_at < ?
                    """,
                    (stale_before,),
                )
                existing = connection.execute(
                    """
                    SELECT 1
                      FROM agent_sessions
                     WHERE idempotency_key = ?
                    """,
                    (clean_key,),
                ).fetchone()
                if existing is not None:
                    connection.commit()
                    return False

                cursor = connection.execute(
                    """
                    INSERT OR IGNORE INTO idempotency_claims (
                        idempotency_key,
                        claimed_at
                    ) VALUES (?, ?)
                    """,
                    (clean_key, now),
                )
                acquired = cursor.rowcount == 1
                connection.commit()
                return acquired

    def release_claim(self, key: str) -> None:
        clean_key = key.strip()
        if not clean_key:
            return
        with self._lock:
            with self._connect() as connection:
                connection.execute(
                    """
                    DELETE FROM idempotency_claims
                     WHERE idempotency_key = ?
                    """,
                    (clean_key,),
                )
                connection.commit()

    def get_by_session_id(self, session_id: str) -> AgentSessionView | None:
        clean_session_id = session_id.strip()
        if not clean_session_id:
            return None

        with self._connect() as connection:
            row = connection.execute(
                """
                SELECT session_id, state, environment_id, repository,
                       base_branch, base_sha, events_path
                  FROM agent_sessions
                 WHERE session_id = ?
                """,
                (clean_session_id,),
            ).fetchone()
        return self._row_to_view(row)

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

        with self._lock:
            with self._connect() as connection:
                connection.execute("BEGIN IMMEDIATE")
                existing = connection.execute(
                    """
                    SELECT session_id, state, environment_id, repository,
                           base_branch, base_sha, events_path
                      FROM agent_sessions
                     WHERE idempotency_key = ?
                    """,
                    (clean_key,),
                ).fetchone()
                if existing is not None:
                    connection.commit()
                    return self._row_to_view(existing)

                try:
                    connection.execute(
                        """
                        INSERT INTO agent_sessions (
                            idempotency_key,
                            session_id,
                            state,
                            environment_id,
                            repository,
                            base_branch,
                            base_sha,
                            events_path
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                        (
                            clean_key,
                            value.session_id,
                            value.state,
                            value.environment_id,
                            value.repository,
                            value.base_branch,
                            value.base_sha,
                            value.events_path,
                        ),
                    )
                except sqlite3.IntegrityError as error:
                    connection.rollback()
                    raise ValueError(
                        "Agent session id is already associated with another request"
                    ) from error

                connection.commit()
                return value

    def _initialize(self) -> None:
        with self._lock:
            with self._connect() as connection:
                connection.execute(
                    """
                    CREATE TABLE IF NOT EXISTS idempotency_claims (
                        idempotency_key TEXT PRIMARY KEY,
                        claimed_at INTEGER NOT NULL
                    )
                    """
                )
                connection.execute(
                    """
                    CREATE TABLE IF NOT EXISTS agent_sessions (
                        idempotency_key TEXT PRIMARY KEY,
                        session_id TEXT NOT NULL UNIQUE,
                        state TEXT NOT NULL,
                        environment_id TEXT,
                        repository TEXT NOT NULL,
                        base_branch TEXT NOT NULL,
                        base_sha TEXT NOT NULL,
                        events_path TEXT NOT NULL
                    )
                    """
                )
                connection.commit()

    def _connect(self) -> sqlite3.Connection:
        connection = sqlite3.connect(
            self._path,
            timeout=30.0,
            isolation_level=None,
        )
        connection.row_factory = sqlite3.Row
        connection.execute("PRAGMA foreign_keys = ON")
        if self._path != ":memory:":
            connection.execute("PRAGMA journal_mode = WAL")
            connection.execute("PRAGMA synchronous = NORMAL")
        return connection

    @staticmethod
    def _row_to_view(row: sqlite3.Row | None) -> AgentSessionView | None:
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
