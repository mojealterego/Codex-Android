import json
import subprocess
import sys
from pathlib import Path

from app.change_exporter import EXPORTER_SCRIPT


def run(*args, cwd):
    subprocess.run(
        args,
        cwd=cwd,
        check=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )


def test_standalone_exporter_builds_text_change_manifest(tmp_path):
    repo = tmp_path / "repo"
    repo.mkdir()
    run("git", "init", "-q", cwd=repo)
    run("git", "config", "user.email", "test@example.invalid", cwd=repo)
    run("git", "config", "user.name", "Test", cwd=repo)

    (repo / "modify.txt").write_text("before\n")
    (repo / "delete.txt").write_text("delete me\n")
    (repo / "rename.txt").write_text("rename me\n")
    run("git", "add", "-A", cwd=repo)
    run("git", "commit", "-qm", "baseline", cwd=repo)

    (repo / "modify.txt").write_text("after\n")
    (repo / "delete.txt").unlink()
    (repo / "rename.txt").rename(repo / "renamed.txt")
    (repo / "new.txt").write_text("new\n")

    script = tmp_path / "export.py"
    script.write_text(EXPORTER_SCRIPT)
    output = tmp_path / "changes.json"

    subprocess.run(
        [
            sys.executable,
            str(script),
            str(repo),
            str(output),
            "base123",
        ],
        check=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )

    manifest = json.loads(output.read_text())
    assert manifest["version"] == 1
    assert manifest["base_sha"] == "base123"

    changes = {item["path"]: item for item in manifest["files"]}
    assert changes["modify.txt"]["operation"] == "upsert"
    assert changes["modify.txt"]["content"] == "after\n"
    assert changes["delete.txt"]["operation"] == "delete"
    assert changes["delete.txt"]["content"] is None
    assert changes["new.txt"]["operation"] == "upsert"
    assert changes["new.txt"]["content"] == "new\n"

    rename = changes["renamed.txt"]
    assert rename["operation"] == "rename"
    assert rename["rename_from"] == "rename.txt"
    assert rename["content"] == "rename me\n"
