import hashlib
import io
import tarfile
from types import SimpleNamespace

from app.agent_service import AgentTask
from app.github_workspace_preparer import GithubArchiveWorkspacePreparer


def build_archive() -> bytes:
    stream = io.BytesIO()
    with tarfile.open(fileobj=stream, mode="w:gz") as archive:
        payload = b"hello\n"
        info = tarfile.TarInfo("repo-root/README.md")
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
        return SimpleNamespace(id="file_uploaded_repo_123")


class FakeOpenAI:
    def __init__(self):
        self.files = FakeFiles()


def test_fetches_pinned_github_archive_and_uploads_it_as_user_data():
    archive = build_archive()
    http = FakeHttpClient(archive)
    openai = FakeOpenAI()
    preparer = GithubArchiveWorkspacePreparer(
        http_client=http,
        openai_client=openai,
        github_token="ghp-secret-value",
    )
    task = AgentTask(
        repo_full_name="mojealterego/Codex-Android",
        base_branch="main",
        base_sha="abc123",
        task="Do work",
        model="gpt-6-astra",
    )

    seed = preparer.prepare(task)

    assert seed.file_id == "file_uploaded_repo_123"
    assert seed.size_bytes == len(archive)
    assert seed.sha256 == hashlib.sha256(archive).hexdigest()

    url, kwargs = http.calls[0]
    assert url == (
        "https://api.github.com/repos/"
        "mojealterego/Codex-Android/tarball/abc123"
    )
    assert kwargs["follow_redirects"] is True
    assert kwargs["headers"]["Authorization"] == "Bearer ghp-secret-value"

    upload = openai.files.calls[0]
    assert upload["purpose"] == "user_data"
    assert upload["file"][0] == "repository.tar.gz"
    assert upload["file"][1] == archive

    assert "ghp-secret-value" not in repr(seed)


def test_rejects_archive_larger_than_environment_file_limit_before_upload():
    http = FakeHttpClient(b"x" * 17)
    openai = FakeOpenAI()
    preparer = GithubArchiveWorkspacePreparer(
        http_client=http,
        openai_client=openai,
        github_token=None,
        max_archive_bytes=16,
    )
    task = AgentTask(
        repo_full_name="owner/repo",
        base_branch="main",
        base_sha="abc123",
        task="Do work",
        model="gpt-6-astra",
    )

    try:
        preparer.prepare(task)
        raise AssertionError("Expected ValueError")
    except ValueError as error:
        assert "archive is too large" in str(error).lower()

    assert openai.files.calls == []
