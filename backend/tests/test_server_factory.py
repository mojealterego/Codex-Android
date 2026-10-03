import io
import tarfile
from types import SimpleNamespace

from app.server import build_runtime_app
from fastapi.testclient import TestClient


def build_archive() -> bytes:
    stream = io.BytesIO()
    with tarfile.open(fileobj=stream, mode="w:gz") as archive:
        payload = b"print('ok')\n"
        info = tarfile.TarInfo("repo-root/main.py")
        info.size = len(payload)
        archive.addfile(info, io.BytesIO(payload))
    return stream.getvalue()


class FakeResponse:
    def __init__(self, content: bytes):
        self.content = content

    def raise_for_status(self):
        return None


class FakeHttpClient:
    def __init__(self, archive: bytes):
        self.archive = archive
        self.calls = []

    def get(self, url, **kwargs):
        self.calls.append((url, kwargs))
        return FakeResponse(self.archive)


class FakeFiles:
    def __init__(self):
        self.calls = []

    def create(self, **kwargs):
        self.calls.append(kwargs)
        return SimpleNamespace(id="file_runtime_repo_123")


class FakeEvents:
    def stream(self, session_id):
        raise AssertionError("SSE stream is not expected in this test")


class FakeSessions:
    def __init__(self):
        self.calls = []
        self.events = FakeEvents()

    def create(self, **kwargs):
        self.calls.append(kwargs)
        return SimpleNamespace(
            id="sess_runtime_123",
            status="in_progress",
            environment=SimpleNamespace(id="env_runtime_123"),
        )


class FakeOpenAI:
    def __init__(self):
        self.files = FakeFiles()
        self.sessions = FakeSessions()
        self.beta = SimpleNamespace(
            agents=SimpleNamespace(
                sessions=self.sessions,
            )
        )


def test_runtime_app_seeds_repository_then_starts_openai_agent_session():
    archive = build_archive()
    http = FakeHttpClient(archive)
    openai = FakeOpenAI()
    app = build_runtime_app(
        openai_client=openai,
        http_client=http,
        github_token="ghp-runtime-secret",
        instructions="Work only inside the seeded repository.",
    )
    client = TestClient(app)

    response = client.post(
        "/v1/agents/sessions",
        headers={"Idempotency-Key": "runtime-task-1"},
        json={
            "repository": "mojealterego/Codex-Android",
            "base_branch": "main",
            "base_sha": "abc123",
            "task": "Implement the next slice.",
            "model": "gpt-6-astra",
        },
    )

    assert response.status_code == 201
    assert response.json()["session_id"] == "sess_runtime_123"
    assert len(http.calls) == 1
    assert len(openai.files.calls) == 1
    assert len(openai.sessions.calls) == 1

    session_request = openai.sessions.calls[0]
    assert session_request["environment"]["network"] == {"access": "disabled"}
    assert session_request["environment"]["files"][0]["file_id"] == (
        "file_runtime_repo_123"
    )
    assert "Task:\nImplement the next slice." in session_request["input"]
    assert "/workspace/outputs/changes.json" in session_request["input"]
    assert "ghp-runtime-secret" not in repr(session_request)
