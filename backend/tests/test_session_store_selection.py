from app.database_session_store import DatabaseSessionStore
from app.server import create_session_store
from app.sqlite_session_store import SqliteSessionStore


def test_prefers_database_url_for_production_persistence(tmp_path):
    store = create_session_store(
        database_url=f"sqlite+pysqlite:///{tmp_path / 'portable.sqlite3'}",
        sqlite_path=str(tmp_path / "fallback.sqlite3"),
    )

    assert isinstance(store, DatabaseSessionStore)


def test_falls_back_to_local_sqlite_when_database_url_is_missing(tmp_path):
    store = create_session_store(
        database_url=None,
        sqlite_path=str(tmp_path / "fallback.sqlite3"),
    )

    assert isinstance(store, SqliteSessionStore)
