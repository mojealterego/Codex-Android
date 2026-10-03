from app.agent_service import AgentSessionView
from app.sqlite_session_store import SqliteSessionStore


def session(session_id="sess_1", state="in_progress"):
    return AgentSessionView(
        session_id=session_id,
        state=state,
        environment_id="env_1",
        repository="owner/repo",
        base_branch="codex/runtime",
        base_sha="abc123",
        events_path=f"/v1/agents/sessions/{session_id}/events",
    )


def test_persists_idempotency_and_session_lookup_across_store_instances(tmp_path):
    database = tmp_path / "sessions.sqlite3"

    first = SqliteSessionStore(database)
    stored = first.put_if_absent("task-001", session())

    second = SqliteSessionStore(database)

    assert second.get("task-001") == stored
    assert second.get_by_session_id("sess_1") == stored


def test_put_if_absent_returns_original_value_for_reused_key(tmp_path):
    database = tmp_path / "sessions.sqlite3"
    store = SqliteSessionStore(database)

    original = store.put_if_absent("task-001", session())
    retried = store.put_if_absent(
        "task-001",
        session(session_id="sess_duplicate", state="idle"),
    )

    assert retried == original
    assert store.get_by_session_id("sess_duplicate") is None


def test_rejects_blank_key_and_session_id(tmp_path):
    store = SqliteSessionStore(tmp_path / "sessions.sqlite3")

    for key, value in [
        ("", session()),
        ("  ", session()),
        ("task", session(session_id="")),
    ]:
        try:
            store.put_if_absent(key, value)
            raise AssertionError("Expected ValueError")
        except ValueError:
            pass
