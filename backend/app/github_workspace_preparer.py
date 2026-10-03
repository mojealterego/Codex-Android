from __future__ import annotations

import hashlib
import io
import tarfile
from pathlib import PurePosixPath
from typing import Any
from urllib.parse import quote

from .agent_service import AgentTask, WorkspaceSeed


class GithubArchiveWorkspacePreparer:
    DEFAULT_MAX_ARCHIVE_BYTES = 50 * 1024 * 1024

    def __init__(
        self,
        http_client: Any,
        openai_client: Any,
        github_token: str | None,
        max_archive_bytes: int = DEFAULT_MAX_ARCHIVE_BYTES,
    ) -> None:
        if max_archive_bytes <= 0:
            raise ValueError("Workspace archive size limit must be positive")

        self._http_client = http_client
        self._openai_client = openai_client
        self._github_token = (github_token or "").strip() or None
        self._max_archive_bytes = max_archive_bytes

    def prepare(self, task: AgentTask) -> WorkspaceSeed:
        archive = self._download_archive(task)
        if len(archive) > self._max_archive_bytes:
            raise ValueError(
                "Repository archive is too large for the agent environment "
                f"({len(archive)} bytes > {self._max_archive_bytes} bytes)"
            )

        self._validate_archive(archive)

        digest = hashlib.sha256(archive).hexdigest()
        uploaded = self._openai_client.files.create(
            file=("repository.tar.gz", archive, "application/gzip"),
            purpose="user_data",
        )
        file_id = str(getattr(uploaded, "id", "")).strip()
        if not file_id:
            raise ValueError("OpenAI Files upload returned an empty file id")

        return WorkspaceSeed(
            file_id=file_id,
            sha256=digest,
            size_bytes=len(archive),
        )

    def _download_archive(self, task: AgentTask) -> bytes:
        owner, name = self._split_repository(task.repo_full_name)
        base_sha = task.base_sha.strip()
        if not base_sha:
            raise ValueError("Base SHA is required")

        url = (
            "https://api.github.com/repos/"
            f"{quote(owner, safe='')}/{quote(name, safe='')}/"
            f"tarball/{quote(base_sha, safe='')}"
        )

        headers = {
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
        }
        if self._github_token is not None:
            headers["Authorization"] = f"Bearer {self._github_token}"

        response = self._http_client.get(
            url,
            headers=headers,
            follow_redirects=True,
        )
        response.raise_for_status()
        return bytes(response.content)

    @staticmethod
    def _split_repository(repo_full_name: str) -> tuple[str, str]:
        parts = repo_full_name.strip("/").split("/")
        if len(parts) != 2 or not all(part.strip() for part in parts):
            raise ValueError("Repository must use owner/name form")
        return parts[0], parts[1]

    @staticmethod
    def _validate_archive(archive: bytes) -> None:
        if not archive:
            raise ValueError("Repository archive is empty")

        try:
            with tarfile.open(fileobj=io.BytesIO(archive), mode="r:gz") as tar:
                members = tar.getmembers()
        except (tarfile.TarError, OSError) as error:
            raise ValueError("Repository archive is not a valid tar.gz") from error

        if not members:
            raise ValueError("Repository archive contains no files")

        roots: set[str] = set()
        for member in members:
            path = PurePosixPath(member.name)
            if path.is_absolute() or ".." in path.parts:
                raise ValueError("Repository archive contains an unsafe path")

            if path.parts:
                roots.add(path.parts[0])

            if member.issym() or member.islnk():
                target = PurePosixPath(member.linkname)
                if target.is_absolute() or ".." in target.parts:
                    raise ValueError(
                        "Repository archive contains an unsafe link target"
                    )

        if len(roots) != 1:
            raise ValueError(
                "Repository archive must contain exactly one top-level directory"
            )
