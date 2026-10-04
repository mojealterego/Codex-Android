import os

from app import server


def test_startup_smoke_runs_only_when_explicitly_enabled(monkeypatch):
    calls = []
    monkeypatch.setattr(server, "runtime_smoke_main", lambda: calls.append("run") or 0)

    monkeypatch.delenv("CODEX_RUNTIME_SMOKE_ON_START", raising=False)
    server.run_startup_smoke_if_requested()
    assert calls == []

    monkeypatch.setenv("CODEX_RUNTIME_SMOKE_ON_START", "true")
    server.run_startup_smoke_if_requested()
    assert calls == ["run"]


def test_startup_smoke_rejects_nonzero_result(monkeypatch):
    monkeypatch.setenv("CODEX_RUNTIME_SMOKE_ON_START", "1")
    monkeypatch.setattr(server, "runtime_smoke_main", lambda: 7)

    try:
        server.run_startup_smoke_if_requested()
        raise AssertionError("Expected RuntimeError")
    except RuntimeError as error:
        assert "smoke" in str(error).lower()



def test_startup_rehydration_smoke_runs_only_when_session_id_is_configured(monkeypatch):
    calls = []
    monkeypatch.setattr(
        server,
        "rehydration_smoke_main",
        lambda: calls.append("rehydrate") or 0,
    )

    monkeypatch.delenv("CODEX_REHYDRATE_SMOKE_SESSION_ID", raising=False)
    server.run_startup_rehydration_smoke_if_requested()
    assert calls == []

    monkeypatch.setenv("CODEX_REHYDRATE_SMOKE_SESSION_ID", "sess_existing")
    server.run_startup_rehydration_smoke_if_requested()
    assert calls == ["rehydrate"]


def test_startup_rehydration_smoke_rejects_nonzero_result(monkeypatch):
    monkeypatch.setenv("CODEX_REHYDRATE_SMOKE_SESSION_ID", "sess_existing")
    monkeypatch.setattr(server, "rehydration_smoke_main", lambda: 9)

    try:
        server.run_startup_rehydration_smoke_if_requested()
        raise AssertionError("Expected RuntimeError")
    except RuntimeError as error:
        assert "rehydration smoke" in str(error).lower()
