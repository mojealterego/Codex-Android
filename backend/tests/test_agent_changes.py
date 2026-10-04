import json
from types import SimpleNamespace

from app.agent_changes import OpenAIChangeSetCollector
from app.agent_service import AgentSessionView


class FakeArtifactResponse:
    def __init__(self, payload: bytes):
        self.payload = payload

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc, tb):
        return False

    def read(self):
        return self.payload


class FakeArtifactContent:
    def __init__(self, owner):
        self.owner = owner

    def content(self, artifact_id, *, session_id):
        self.owner.content_calls.append((artifact_id, session_id))
        return FakeArtifactResponse(self.owner.payload)


class FakeArtifacts:
    def __init__(self, payload):
        self.payload = payload
        self.content_calls = []
        self.with_streaming_response = FakeArtifactContent(self)

    def list(self, session_id):
        assert session_id == "sess_123"
        return [
            SimpleNamespace(
                id="artifact_old",
                turn_id="turn_old",
                path="/workspace/outputs/changes.json",
            ),
            SimpleNamespace(
                id="artifact_latest",
                turn_id="turn_latest",
                path="/workspace/outputs/changes.json",
            ),
        ]


class FakeTurns:
    def list(self, session_id, *, order, limit):
        assert session_id == "sess_123"
        assert order == "desc"
        assert limit == 20
        return [
            SimpleNamespace(id="turn_latest", completed_at=123),
            SimpleNamespace(id="turn_old", completed_at=100),
        ]


class FakeOpenAI:
    def __init__(self, payload):
        artifacts = FakeArtifacts(payload)
        self.artifacts = artifacts
        self.beta = SimpleNamespace(
            agents=SimpleNamespace(
                sessions=SimpleNamespace(
                    turns=FakeTurns(),
                    artifacts=artifacts,
                )
            )
        )


def session():
    return AgentSessionView(
        session_id="sess_123",
        state="idle",
        environment_id="env_123",
        repository="mojealterego/Codex-Android",
        base_branch="codex/agent-runtime-p2",
        base_sha="base123",
        events_path="/v1/agents/sessions/sess_123/events",
    )


def test_collects_latest_completed_turn_changes_artifact_and_validates_base():
    payload = json.dumps({
        "version": 1,
        "base_sha": "base123",
        "files": [
            {
                "path": "app/src/main/Test.kt",
                "operation": "upsert",
                "mode": "100644",
                "content": "package test\n",
                "diff": "@@ -0,0 +1 @@\n+package test",
            },
            {
                "path": "old.txt",
                "operation": "delete",
                "mode": "100644",
                "content": None,
                "diff": "@@ -1 +0,0 @@\n-old",
            },
        ],
    }).encode()

    client = FakeOpenAI(payload)
    result = OpenAIChangeSetCollector(client).collect(session())

    assert result.session_id == "sess_123"
    assert result.turn_id == "turn_latest"
    assert result.base_branch == "codex/agent-runtime-p2"
    assert result.base_sha == "base123"
    assert len(result.files) == 2
    assert result.files[0].operation == "upsert"
    assert result.files[1].operation == "delete"
    assert client.artifacts.content_calls == [
        ("artifact_latest", "sess_123")
    ]


def test_rejects_manifest_from_different_base_sha():
    payload = json.dumps({
        "version": 1,
        "base_sha": "wrong-base",
        "files": [{
            "path": "README.md",
            "operation": "upsert",
            "mode": "100644",
            "content": "changed",
            "diff": "diff",
        }],
    }).encode()

    try:
        OpenAIChangeSetCollector(FakeOpenAI(payload)).collect(session())
        raise AssertionError("Expected ValueError")
    except ValueError as error:
        assert "base sha" in str(error).lower()


def test_rejects_unsafe_path_and_binary_marker():
    payload = json.dumps({
        "version": 1,
        "base_sha": "base123",
        "files": [{
            "path": "../secret",
            "operation": "upsert",
            "mode": "100644",
            "content": "x",
            "diff": "Binary files differ",
        }],
    }).encode()

    try:
        OpenAIChangeSetCollector(FakeOpenAI(payload)).collect(session())
        raise AssertionError("Expected ValueError")
    except ValueError:
        pass
