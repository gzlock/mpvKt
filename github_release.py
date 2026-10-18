#!/usr/bin/env python3
"""Bump the Android version and publish a GitHub release tag."""

from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parent
GRADLE_FILE = ROOT / "app" / "build.gradle.kts"
VERSION_CODE_PATTERN = re.compile(r"^(\s*versionCode\s*=\s*)(\d+)(\s*)$", re.MULTILINE)
VERSION_NAME_PATTERN = re.compile(r'^(\s*versionName\s*=\s*)"([^"]+)"(\s*)$', re.MULTILINE)
VERSION_PATTERN = re.compile(r"^\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?$")


def run_git(*args: str, capture: bool = False) -> str:
    """Run a git command from the repository root."""
    result = subprocess.run(
        ["git", *args],
        cwd=ROOT,
        check=False,
        text=True,
        capture_output=capture,
    )
    if result.returncode != 0:
        if capture and result.stderr:
            print(result.stderr.strip(), file=sys.stderr)
        raise RuntimeError(f"git {' '.join(args)} failed with exit code {result.returncode}")
    return result.stdout if capture else ""


def confirm(prompt: str) -> bool:
    try:
        return input(f"{prompt} [y/N]: ").strip().lower() in {"y", "yes"}
    except EOFError:
        return False


def read_versions() -> tuple[int, str]:
    text = GRADLE_FILE.read_text(encoding="utf-8")
    code_match = VERSION_CODE_PATTERN.search(text)
    name_match = VERSION_NAME_PATTERN.search(text)
    if code_match is None or name_match is None:
        raise RuntimeError(f"Could not find versionCode/versionName in {GRADLE_FILE}")
    return int(code_match.group(2)), name_match.group(2)


def tag_exists(tag: str) -> bool:
    local = subprocess.run(
        ["git", "show-ref", "--verify", "--quiet", f"refs/tags/{tag}"],
        cwd=ROOT,
        check=False,
    )
    if local.returncode == 0:
        return True

    remote = subprocess.run(
        ["git", "ls-remote", "--exit-code", "--tags", "origin", f"refs/tags/{tag}"],
        cwd=ROOT,
        check=False,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    return remote.returncode == 0


def main() -> int:
    try:
        current_code, current_name = read_versions()
        print(f"Current versionCode: {current_code}")
        print(f"Current versionName: {current_name}")

        new_name = input("New versionName: ").strip()
        if not VERSION_PATTERN.fullmatch(new_name):
            print("Invalid version. Use a value such as 0.1.8 or 0.1.8-beta1.", file=sys.stderr)
            return 1

        tag = f"v{new_name}"
        if tag_exists(tag):
            print(f"Tag {tag} already exists locally or on origin.", file=sys.stderr)
            return 1

        print(f"\nRelease summary:\n  versionCode: {current_code} -> {current_code + 1}\n"
              f"  versionName: {current_name} -> {new_name}\n  tag: {tag}")
        if not confirm("Continue and stage all repository changes?"):
            print("Cancelled.")
            return 0

        text = GRADLE_FILE.read_text(encoding="utf-8")
        text, code_count = VERSION_CODE_PATTERN.subn(
            lambda match: f"{match.group(1)}{current_code + 1}{match.group(3)}", text, count=1
        )
        text, name_count = VERSION_NAME_PATTERN.subn(
            lambda match: f'{match.group(1)}"{new_name}"{match.group(3)}', text, count=1
        )
        if code_count != 1 or name_count != 1:
            raise RuntimeError("Could not update exactly one versionCode and versionName")
        GRADLE_FILE.write_text(text, encoding="utf-8", newline="")

        run_git("add", "-A")
        staged = run_git("diff", "--cached", "--name-status", capture=True).strip()
        if not staged:
            raise RuntimeError("No changes were staged")
        print("\nStaged changes:")
        print(staged)
        if not confirm("Commit, push the branch, create the tag, and push the tag?"):
            print("Cancelled after staging. The version file remains updated and changes remain staged.")
            return 0

        run_git("commit", "-m", f"release: {tag}")
        run_git("push", "origin", "HEAD")
        run_git("tag", "-a", tag, "-m", f"Release {tag}")
        run_git("push", "origin", tag)
        print(f"Published {tag}. GitHub Actions should now build the release.")
        return 0
    except (RuntimeError, OSError, ValueError) as error:
        print(f"Error: {error}", file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print("\nCancelled.", file=sys.stderr)
        return 130


if __name__ == "__main__":
    raise SystemExit(main())
