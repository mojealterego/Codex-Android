import base64
from types import SimpleNamespace

from app.agent_service import AgentTask, WorkspaceSeed
from app.change_exporter import EXPORTER_SCRIPT
from app.openai_agents_runtime import OpenAIAgentsRuntime


class Sessions:
    def __init__(self):
        self.last_kwargs = None

    def create(self, **kwargs):
        self.last_kwargs = kwargs
        return SimpleNamespace(
            id="sess_export",
            status="in_progress",
            environment=SimpleNamespace(id="env_export"),
        )


class Client:
    def __init__(self):
        self.sessions = Sessions()
        self.beta = SimpleNamespace(
            agents=SimpleNamespace(sessions=self.sessions)
        )


def test_runtime_initializes_local_git_baseline_and_injects_exporter():
    client = Client()
    runtime = OpenAIAgentsRuntime(
        client=client,
        instructions="Work safely.",
    )
    runtime.create_session(
        AgentTask(
            repo_full_name="owner/repo",
            base_branch="codex/review",
            base_sha="base123",
            task="Change the repository.",
            model="gpt-6-astra",
        ),
        WorkspaceSeed(
            file_id="file_repo",
            sha256="archive-sha",
            size_bytes=123,
        ),
    )

    request = client.sessions.last_kwargs
    files = request["environment"]["files"]
    assert files[0]["file_id"] == "file_repo"
    assert files[1]["type"] == "inline"
    assert files[1]["path"] == "/workspace/change_exporter.py"
    assert base64.b64decode(files[1]["data"]).decode() == EXPORTER_SCRIPT

    setup = request["environment"]["setup_commands"][0]["command"]
    assert "git init -q" in setup
    assert "git add -A" in setup
    assert "git commit -qm baseline" in setup

    assert (
        "python /workspace/change_exporter.py "
        "/workspace/repository "
        "/workspace/outputs/changes.json "
        "base123"
    ) in request["input"]
