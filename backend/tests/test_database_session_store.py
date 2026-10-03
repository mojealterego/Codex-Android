from app.agent_service import AgentSessionView
from app.database_session_store import DatabaseSessionStore


def session(session_id="sess_db", state="in_progress"):
    return AgentSessionView(
        session_id=session_id,
        state=state,
        environment_id="env_db",
        repository="owner/repo",
        base_branch="codex/db",
        base_sha="abc123",
        events_path=f"/v1/agents/sessions/{session_id}/events",
    )


def test_database_store_persists_across_instances(tmp_path):
    database = tmp_path / "state.sqlite3"
    url = f"sqlite+pysqlite:///{database}"

    first = DatabaseSessionStore(url)
    stored = first.put_if_absent("task-db", session())

    second = DatabaseSessionStore(url)

    assert second.get("task-db") == stored
    assert second.get_by_session_id("sess_db") == stored


def test_database_store_claim_is_atomic_across_instances(tmp_path):
    database = tmp_path / "state.sqlite3"
    url = f"sqlite+pysqlite:///{database}"

    first = DatabaseSessionStore(url)
    second = DatabaseSessionStore(url)

    assert first.try_claim("same-key") is True
    assert second.try_claim("same-key") is False

    first.release_claim("same-key")
    assert second.try_claim("same-key") is True


def test_database_store_idempotent_put_returns_original(tmp_path):
    database = tmp_path / "state.sqlite3"
    store = DatabaseSessionStore(f"sqlite+pysqlite:///{database}")

    original = store.put_if_absent("task-db", session())
    duplicate = store.put_if_absent(
        "task-db",
        session(session_id="sess_other", state="idle"),
    )

    assert duplicate == original
    assert store.get_by_session_id("sess_other") is None
