from types import SimpleNamespace

from app.openai_session_resolver import OpenAIAgentSessionResolver


class FakeSessions:
    def __init__(self, remote):
        self.remote = remote
        self.calls = []

    def retrieve(self, session_id):
        self.calls.append(session_id)
        return self.remote


class FakeClient:
    def __init__(self, remote):
        self.sessions = FakeSessions(remote)
        self.beta = SimpleNamespace(
            agents=SimpleNamespace(sessions=self.sessions)
        )


def remote_session(metadata):
    return SimpleNamespace(
        id="sess_remote",
        status="idle",
        environment=SimpleNamespace(id="env_remote"),
        metadata=metadata,
    )


def test_rehydrates_codex_android_session_from_remote_metadata():
    client = FakeClient(remote_session({
        "codex_android_schema": "1",
        "repository": "mojealterego/Codex-Android",
        "base_branch": "codex/recovery",
        "base_sha": "abc123",
    }))

    result = OpenAIAgentSessionResolver(client).resolve("sess_remote")

    assert result is not None
    assert result.session_id == "sess_remote"
    assert result.state == "idle"
    assert result.environment_id == "env_remote"
    assert result.repository == "mojealterego/Codex-Android"
    assert result.base_branch == "codex/recovery"
    assert result.base_sha == "abc123"
    assert result.events_path == "/v1/agents/sessions/sess_remote/events"


def test_rejects_remote_session_without_codex_android_ownership_marker():
    client = FakeClient(remote_session({
        "repository": "mojealterego/Codex-Android",
        "base_branch": "codex/recovery",
        "base_sha": "abc123",
    }))

    assert OpenAIAgentSessionResolver(client).resolve("sess_remote") is None


def test_rejects_owned_session_outside_codex_branch():
    client = FakeClient(remote_session({
        "codex_android_schema": "1",
        "repository": "mojealterego/Codex-Android",
        "base_branch": "main",
        "base_sha": "abc123",
    }))

    assert OpenAIAgentSessionResolver(client).resolve("sess_remote") is None
