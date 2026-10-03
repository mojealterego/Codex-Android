from __future__ import annotations

EXPORTER_SCRIPT = r'''#!/usr/bin/env python3
import json
import os
import stat
import subprocess
import sys
from pathlib import Path, PurePosixPath


VALID_MODES = {"100644", "100755", "120000"}
MAX_FILE_BYTES = 2 * 1024 * 1024
MAX_FILES = 200


def git(repo, *args, binary=False):
    result = subprocess.run(
        ["git", "-C", str(repo), *args],
        check=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    return result.stdout if binary else result.stdout.decode("utf-8")


def safe_relative(path):
    normalized = path.replace("\\", "/").strip("/")
    parsed = PurePosixPath(normalized)
    if not normalized or parsed.is_absolute() or ".." in parsed.parts or "." in parsed.parts:
        raise ValueError("unsafe repository path")
    return parsed.as_posix()


def mode_for_worktree(path):
    info = os.lstat(path)
    if stat.S_ISLNK(info.st_mode):
        return "120000"
    if not stat.S_ISREG(info.st_mode):
        raise ValueError("only regular files and symlinks are supported")
    return "100755" if info.st_mode & stat.S_IXUSR else "100644"


def mode_from_head(repo, path):
    raw = git(repo, "ls-tree", "-z", "HEAD", "--", path, binary=True)
    if not raw:
        return "100644"
    metadata = raw.split(b"\t", 1)[0].decode("ascii")
    mode = metadata.split(" ", 1)[0]
    if mode not in VALID_MODES:
        raise ValueError("unsupported git mode")
    return mode


def read_text(path, mode):
    if mode == "120000":
        return os.readlink(path)
    data = Path(path).read_bytes()
    if len(data) > MAX_FILE_BYTES:
        raise ValueError("changed file is too large")
    if b"\x00" in data:
        raise ValueError("binary files are not supported")
    try:
        return data.decode("utf-8")
    except UnicodeDecodeError as error:
        raise ValueError("changed file must be UTF-8 text") from error


def patch_for(repo, *paths):
    text = git(
        repo,
        "diff",
        "--no-ext-diff",
        "--unified=3",
        "HEAD",
        "--",
        *paths,
    )
    if "Binary files" in text or "GIT binary patch" in text:
        raise ValueError("binary diffs are not supported")
    return text


def parse_name_status(raw):
    tokens = [item for item in raw.split(b"\x00") if item]
    result = []
    index = 0
    while index < len(tokens):
        status_token = tokens[index].decode("ascii")
        index += 1
        kind = status_token[:1]
        if kind in {"R", "C"}:
            if index + 1 >= len(tokens):
                raise ValueError("invalid rename diff")
            old = safe_relative(tokens[index].decode("utf-8"))
            new = safe_relative(tokens[index + 1].decode("utf-8"))
            index += 2
            result.append((kind, old, new))
        else:
            if index >= len(tokens):
                raise ValueError("invalid diff")
            path = safe_relative(tokens[index].decode("utf-8"))
            index += 1
            result.append((kind, path, None))
    return result


def export(repo_path, output_path, base_sha):
    repo = Path(repo_path).resolve()
    output = Path(output_path).resolve()
    output.parent.mkdir(parents=True, exist_ok=True)

    git(repo, "rev-parse", "--verify", "HEAD")
    subprocess.run(
        ["git", "-C", str(repo), "add", "-N", "--", "."],
        check=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )

    raw = git(
        repo,
        "diff",
        "--name-status",
        "-z",
        "-M",
        "HEAD",
        "--",
        binary=True,
    )
    entries = parse_name_status(raw)
    if len(entries) > MAX_FILES:
        raise ValueError("too many changed files")

    files = []
    for kind, first, second in entries:
        if kind == "D":
            files.append({
                "path": first,
                "operation": "delete",
                "mode": mode_from_head(repo, first),
                "content": None,
                "diff": patch_for(repo, first),
                "rename_from": None,
            })
            continue

        if kind == "R":
            destination = second
            assert destination is not None
            mode = mode_for_worktree(repo / destination)
            files.append({
                "path": destination,
                "operation": "rename",
                "mode": mode,
                "content": read_text(repo / destination, mode),
                "diff": patch_for(repo, first, destination),
                "rename_from": first,
            })
            continue

        if kind == "C":
            destination = second
            assert destination is not None
            mode = mode_for_worktree(repo / destination)
            files.append({
                "path": destination,
                "operation": "upsert",
                "mode": mode,
                "content": read_text(repo / destination, mode),
                "diff": patch_for(repo, destination),
                "rename_from": None,
            })
            continue

        if kind not in {"A", "M", "T"}:
            raise ValueError("unsupported git change status: " + kind)

        mode = mode_for_worktree(repo / first)
        files.append({
            "path": first,
            "operation": "upsert",
            "mode": mode,
            "content": read_text(repo / first, mode),
            "diff": patch_for(repo, first),
            "rename_from": None,
        })

    if not files:
        raise ValueError("agent produced no repository changes")

    manifest = {
        "version": 1,
        "base_sha": base_sha,
        "files": files,
    }
    output.write_text(
        json.dumps(manifest, ensure_ascii=False, separators=(",", ":")),
        encoding="utf-8",
    )


if __name__ == "__main__":
    if len(sys.argv) != 4:
        raise SystemExit(
            "usage: change_exporter.py REPO OUTPUT BASE_SHA"
        )
    export(sys.argv[1], sys.argv[2], sys.argv[3])
'''
