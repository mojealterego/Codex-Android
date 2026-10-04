from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import PurePosixPath
from typing import Any

from .agent_service import AgentSessionView


VALID_MODES = {"100644", "100755", "120000", "160000"}
VALID_OPERATIONS = {"upsert", "delete", "rename"}
MANIFEST_PATH = "/workspace/outputs/changes.json"
MAX_FILES = 200
MAX_TOTAL_TEXT_BYTES = 10 * 1024 * 1024


@dataclass(frozen=True)
class AgentFileChange:
    path: str
    operation: str
    mode: str
    content: str | None
    diff: str
    rename_from: str | None = None


@dataclass(frozen=True)
class AgentChangeSet:
    session_id: str
    turn_id: str
    base_branch: str
    base_sha: str
    files: tuple[AgentFileChange, ...]


class OpenAIChangeSetCollector:
    def __init__(self, client: Any) -> None:
        self._client = client

    def collect(self, session: AgentSessionView) -> AgentChangeSet:
        turns = list(
            self._client.beta.agents.sessions.turns.list(
                session.session_id,
                order="desc",
                limit=20,
            )
        )
        artifacts = list(
            self._client.beta.agents.sessions.artifacts.list(session.session_id)
        )

        selected_turn = None
        selected_artifact = None

        for turn in turns:
            if getattr(turn, "completed_at", None) is None:
                continue

            turn_id = str(getattr(turn, "id", ""))
            for artifact in artifacts:
                if (
                    str(getattr(artifact, "turn_id", "")) == turn_id
                    and str(getattr(artifact, "path", "")) == MANIFEST_PATH
                ):
                    selected_turn = turn
                    selected_artifact = artifact
                    break

            if selected_artifact is not None:
                break

        if selected_turn is None or selected_artifact is None:
            raise ValueError(
                "No completed agent turn published /workspace/outputs/changes.json"
            )

        payload = self._download_artifact(
            session_id=session.session_id,
            artifact_id=str(selected_artifact.id),
        )
        manifest = self._parse_manifest(payload)

        base_sha = str(manifest.get("base_sha") or "").strip()
        if base_sha != session.base_sha:
            raise ValueError(
                "Agent change manifest base SHA does not match the pinned session base SHA"
            )

        files_raw = manifest.get("files")
        if not isinstance(files_raw, list) or not files_raw:
            raise ValueError("Agent change manifest must contain at least one file")
        if len(files_raw) > MAX_FILES:
            raise ValueError("Agent change manifest contains too many files")

        files: list[AgentFileChange] = []
        total_text_bytes = 0

        for raw in files_raw:
            if not isinstance(raw, dict):
                raise ValueError("Each agent file change must be an object")

            change = self._parse_file(raw)
            total_text_bytes += len((change.content or "").encode("utf-8"))
            total_text_bytes += len(change.diff.encode("utf-8"))
            if total_text_bytes > MAX_TOTAL_TEXT_BYTES:
                raise ValueError("Agent change manifest is too large")

            files.append(change)

        normalized = [item.path for item in files]
        if len(normalized) != len(set(normalized)):
            raise ValueError("Agent change manifest contains duplicate file paths")

        return AgentChangeSet(
            session_id=session.session_id,
            turn_id=str(selected_turn.id),
            base_branch=session.base_branch,
            base_sha=session.base_sha,
            files=tuple(files),
        )

    def _download_artifact(self, session_id: str, artifact_id: str) -> bytes:
        with (
            self._client.beta.agents.sessions.artifacts.with_streaming_response.content(
                artifact_id,
                session_id=session_id,
            )
        ) as response:
            data = response.read()

        if isinstance(data, str):
            return data.encode("utf-8")
        if isinstance(data, (bytes, bytearray)):
            return bytes(data)
        raise ValueError("Agent change artifact returned unsupported content")

    @staticmethod
    def _parse_manifest(payload: bytes) -> dict[str, Any]:
        if not payload:
            raise ValueError("Agent change artifact is empty")

        try:
            decoded = payload.decode("utf-8")
        except UnicodeDecodeError as error:
            raise ValueError("Agent change artifact must be UTF-8 JSON") from error

        try:
            manifest = json.loads(decoded)
        except json.JSONDecodeError as error:
            raise ValueError("Agent change artifact is not valid JSON") from error

        if not isinstance(manifest, dict):
            raise ValueError("Agent change artifact root must be an object")
        if manifest.get("version") != 1:
            raise ValueError("Unsupported agent change artifact version")

        return manifest

    @staticmethod
    def _parse_file(raw: dict[str, Any]) -> AgentFileChange:
        path = OpenAIChangeSetCollector._safe_relative_path(raw.get("path"), "path")
        operation = str(raw.get("operation") or "").strip().lower()
        mode = str(raw.get("mode") or "").strip()
        diff = raw.get("diff")
        content = raw.get("content")
        rename_from = raw.get("rename_from")

        if operation not in VALID_OPERATIONS:
            raise ValueError("Unsupported agent file operation")
        if mode not in VALID_MODES:
            raise ValueError("Unsupported git mode in agent change manifest")
        if not isinstance(diff, str):
            raise ValueError("Agent file change diff must be text")
        if "Binary files" in diff or "GIT binary patch" in diff:
            raise ValueError("Binary agent changes are not supported")

        if operation in {"upsert", "rename"}:
            if not isinstance(content, str):
                raise ValueError(f"{operation} requires UTF-8 text content")
        elif content is not None:
            raise ValueError("delete operation must not include content")

        normalized_rename = None
        if operation == "rename":
            normalized_rename = OpenAIChangeSetCollector._safe_relative_path(
                rename_from,
                "rename_from",
            )
            if normalized_rename == path:
                raise ValueError("Rename source and destination must differ")
        elif rename_from not in (None, ""):
            raise ValueError("rename_from is only valid for rename operations")

        return AgentFileChange(
            path=path,
            operation=operation,
            mode=mode,
            content=content if isinstance(content, str) else None,
            diff=diff,
            rename_from=normalized_rename,
        )

    @staticmethod
    def _safe_relative_path(value: Any, field: str) -> str:
        if not isinstance(value, str):
            raise ValueError(f"Agent change {field} must be text")

        clean = value.strip().replace("\\", "/").strip("/")
        path = PurePosixPath(clean)

        if (
            not clean
            or path.is_absolute()
            or ".." in path.parts
            or "." in path.parts
        ):
            raise ValueError(f"Unsafe agent change {field}")

        return path.as_posix()
