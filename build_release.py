#!/usr/bin/env python3
"""Build the Android release APKs and optionally sign them.

The current Gradle configuration does not assign a signing config to the
``release`` build type, so Gradle produces ``*-release-unsigned.apk`` files.
This script keeps signing outside Gradle, which also matches the GitHub Actions
release workflow.

Examples::

    # Build release APKs. If no signing variables are present, keep them unsigned.
    python build_release.py

    # Build and sign using a Base64-encoded keystore from .env.
    python build_release.py --sign

    # Only process APKs that were built already.
    python build_release.py --skip-build --unsigned

For unattended local use, the signing options can also be supplied through
``BUILD_RELEASE_*`` variables. Use ``BUILD_RELEASE_KEYSTORE`` for a local
keystore path or ``BUILD_RELEASE_KEYSTORE_BASE64`` for a Base64-encoded JKS.
The older ``SIGNING_KEYSTORE``, ``SIGNING_KEY_ALIAS``,
``SIGNING_STORE_PASSWORD`` and ``KEY_PASSWORD`` names are accepted as aliases;
``SIGNING_KEYSTORE`` may contain either a local path or a Base64 value.

The repository-root ``.env`` file is loaded automatically. Values already
present in the process environment take precedence. Use ``--env-file`` to
load another file, or ``--no-env-file`` to disable dotenv loading.
"""

from __future__ import annotations

import argparse
import base64
import binascii
import hashlib
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path
from typing import Sequence


ROOT = Path(__file__).resolve().parent
DEFAULT_APK_DIR = ROOT / "app" / "build" / "outputs" / "apk" / "release"
DEFAULT_OUTPUT_DIR = ROOT / "dist" / "release"
DEFAULT_ENV_FILE = ROOT / ".env"


class BuildReleaseError(RuntimeError):
    """An expected error while building, signing, or collecting APKs."""


def _parse_dotenv_value(raw_value: str, *, path: Path, line_number: int) -> str:
    """Parse a dotenv value without interpreting backslashes in Windows paths."""

    value = raw_value.strip()
    if not value:
        return ""

    if value[0] in {"'", '"'}:
        quote = value[0]
        closing = value.find(quote, 1)
        if closing < 0:
            raise BuildReleaseError(f"Invalid quoted value in {path} at line {line_number}")
        trailing = value[closing + 1 :].strip()
        if trailing and not trailing.startswith("#"):
            raise BuildReleaseError(f"Invalid quoted value in {path} at line {line_number}")
        return value[1:closing]

    # In an unquoted value, a # preceded by whitespace starts a comment. This
    # keeps passwords or paths containing a literal '#' usable when quoted.
    comment = re.search(r"\s+#", value)
    return value[: comment.start()].rstrip() if comment else value


def _load_dotenv(path: Path) -> None:
    """Load simple KEY=VALUE dotenv entries without overwriting env vars."""

    if not path.is_file():
        return

    try:
        lines = path.read_text(encoding="utf-8-sig").splitlines()
    except OSError as error:
        raise BuildReleaseError(f"Could not read dotenv file {path}: {error}") from error

    key_pattern = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*$")
    for line_number, line in enumerate(lines, start=1):
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        if stripped.startswith("export "):
            stripped = stripped[7:].lstrip()
        if "=" not in stripped:
            raise BuildReleaseError(f"Invalid dotenv entry in {path} at line {line_number}")

        key, raw_value = stripped.split("=", 1)
        key = key.strip()
        if not key_pattern.fullmatch(key):
            raise BuildReleaseError(f"Invalid dotenv key {key!r} in {path} at line {line_number}")
        value = _parse_dotenv_value(raw_value, path=path, line_number=line_number)
        if key not in os.environ:
            os.environ[key] = value


def _path_from_root(value: str | Path) -> Path:
    """Resolve a CLI path relative to the repository root."""

    path = Path(value).expanduser()
    return path if path.is_absolute() else ROOT / path


def _non_empty_environment(*names: str) -> str | None:
    for name in names:
        value = os.environ.get(name)
        if value:
            return value
    return None


def _resolve_signing_value(cli_value: str | None, *environment_names: str) -> str | None:
    if cli_value is not None:
        return cli_value
    return _non_empty_environment(*environment_names)


def _command_text(command: Sequence[str]) -> str:
    """Return a readable command without ever including signing passwords."""

    if os.name == "nt":
        import subprocess as _subprocess

        return _subprocess.list2cmdline([str(part) for part in command])
    import shlex

    return shlex.join(str(part) for part in command)


def _run(command: Sequence[str], *, label: str, env: dict[str, str] | None = None) -> None:
    print(f"[{label}] {_command_text(command)}")
    try:
        subprocess.run(command, cwd=ROOT, env=env, check=True)
    except OSError as error:
        raise BuildReleaseError(f"Could not start {label}: {error}") from error
    except subprocess.CalledProcessError as error:
        raise BuildReleaseError(f"{label} failed with exit code {error.returncode}") from error


def _gradle_command(task: str) -> list[str]:
    wrapper_name = "gradlew.bat" if os.name == "nt" else "gradlew"
    wrapper = ROOT / wrapper_name
    if not wrapper.is_file():
        raise BuildReleaseError(f"Gradle wrapper not found: {wrapper}")
    return [str(wrapper), task]


def _find_apksigner(explicit_path: str | None) -> Path:
    if explicit_path:
        signer = _path_from_root(explicit_path)
        if signer.is_file():
            return signer
        raise BuildReleaseError(f"apksigner was not found: {signer}")

    from_environment = _non_empty_environment("APKSIGNER")
    if from_environment:
        signer = _path_from_root(from_environment)
        if signer.is_file():
            return signer
        raise BuildReleaseError(f"apksigner was not found: {signer}")

    sdk_roots = []
    for variable in ("ANDROID_SDK_ROOT", "ANDROID_HOME"):
        value = os.environ.get(variable)
        if value:
            sdk_root = Path(value).expanduser()
            if sdk_root not in sdk_roots:
                sdk_roots.append(sdk_root)

    candidates: list[Path] = []
    for sdk_root in sdk_roots:
        build_tools = sdk_root / "build-tools"
        if not build_tools.is_dir():
            continue
        for version_dir in build_tools.iterdir():
            if not version_dir.is_dir():
                continue
            for filename in ("apksigner.bat", "apksigner.exe", "apksigner"):
                candidate = version_dir / filename
                if candidate.is_file():
                    candidates.append(candidate)

    if candidates:
        # Prefer the newest installed build-tools version. Keep the preview
        # suffix as a tie-breaker without requiring an extra dependency.
        def version_key(path: Path) -> tuple[int, int, int, int, str]:
            match = re.match(r"^(\d+)(?:\.(\d+))?(?:\.(\d+))?(.*)$", path.parent.name)
            if match is None:
                return (0, 0, 0, 0, path.parent.name.lower())
            suffix = match.group(4).lower()
            return (
                int(match.group(1)),
                int(match.group(2) or 0),
                int(match.group(3) or 0),
                int(suffix == ""),
                suffix,
            )

        return max(candidates, key=version_key)

    executable = shutil.which("apksigner") or shutil.which("apksigner.bat")
    if executable:
        return Path(executable)

    raise BuildReleaseError(
        "Could not locate apksigner. Set APKSIGNER or ANDROID_SDK_ROOT, "
        "or install Android SDK build-tools."
    )


def _discover_apks(apk_dir: Path) -> list[Path]:
    if not apk_dir.is_dir():
        raise BuildReleaseError(f"Release APK directory does not exist: {apk_dir}")

    unsigned = sorted(
        {
            *apk_dir.glob("app-*-release-unsigned.apk"),
            *apk_dir.glob("app-release-unsigned.apk"),
        }
    )
    if unsigned:
        return unsigned

    # This also supports a project that later adds signingConfig directly to
    # the release build type.
    signed = sorted({*apk_dir.glob("app-*-release.apk"), *apk_dir.glob("app-release.apk")})
    if signed:
        return signed

    raise BuildReleaseError(f"No release APKs found in {apk_dir}")


def _signed_name(source: Path) -> str:
    suffix = "-unsigned.apk"
    if source.name.endswith(suffix):
        return source.name[: -len(suffix)] + ".apk"
    return source.name


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _sign_apk(
    signer: Path,
    source: Path,
    destination: Path,
    *,
    keystore: Path,
    alias: str,
    store_password: str,
    key_password: str,
) -> None:
    # Passwords are passed through child-process environment variables instead
    # of appearing in the command line or in the script's output.
    child_environment = os.environ.copy()
    child_environment["BUILD_RELEASE_STORE_PASSWORD"] = store_password
    child_environment["BUILD_RELEASE_KEY_PASSWORD"] = key_password
    command = [
        str(signer),
        "sign",
        "--ks",
        str(keystore),
        "--ks-key-alias",
        alias,
        "--ks-pass",
        "env:BUILD_RELEASE_STORE_PASSWORD",
        "--key-pass",
        "env:BUILD_RELEASE_KEY_PASSWORD",
        "--out",
        str(destination),
        str(source),
    ]
    _run(command, label=f"Sign {source.name}", env=child_environment)

    _run([str(signer), "verify", str(destination)], label=f"Verify {destination.name}")


def _copy_or_sign(
    source: Path,
    output_dir: Path,
    *,
    signer: Path | None,
    keystore: Path | None,
    alias: str | None,
    store_password: str | None,
    key_password: str | None,
) -> Path:
    should_sign = signer is not None
    destination_name = _signed_name(source) if should_sign else source.name
    destination = output_dir / destination_name

    if should_sign and source.name.endswith("-unsigned.apk"):
        assert keystore is not None
        assert alias is not None
        assert store_password is not None
        assert key_password is not None
        _sign_apk(
            signer,
            source,
            destination,
            keystore=keystore,
            alias=alias,
            store_password=store_password,
            key_password=key_password,
        )
    else:
        if source.resolve() != destination.resolve():
            shutil.copy2(source, destination)
        if should_sign:
            _run([str(signer), "verify", str(destination)], label=f"Verify {destination.name}")

    return destination


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    dotenv_mode = parser.add_mutually_exclusive_group()
    dotenv_mode.add_argument(
        "--env-file",
        type=Path,
        help="dotenv file to load (default: repository .env)",
    )
    dotenv_mode.add_argument(
        "--no-env-file",
        action="store_true",
        help="do not load a dotenv file",
    )
    signing_mode = parser.add_mutually_exclusive_group()
    signing_mode.add_argument(
        "--sign",
        action="store_true",
        help="require a keystore and sign the generated APKs",
    )
    signing_mode.add_argument(
        "--unsigned",
        action="store_true",
        help="do not sign APKs, even if signing variables are present",
    )
    parser.add_argument(
        "--keystore",
        help="keystore path (or BUILD_RELEASE_KEYSTORE/SIGNING_KEYSTORE)",
    )
    parser.add_argument(
        "--key-alias",
        "--alias",
        dest="key_alias",
        help="keystore alias (or BUILD_RELEASE_KEY_ALIAS/SIGNING_KEY_ALIAS)",
    )
    parser.add_argument(
        "--store-password",
        help="keystore password (or BUILD_RELEASE_STORE_PASSWORD/SIGNING_STORE_PASSWORD)",
    )
    parser.add_argument(
        "--key-password",
        help="key password (or BUILD_RELEASE_KEY_PASSWORD/KEY_PASSWORD)",
    )
    parser.add_argument(
        "--apksigner",
        help="path to apksigner (or APKSIGNER); discovered from Android SDK by default",
    )
    parser.add_argument(
        "--apk-dir",
        type=Path,
        default=DEFAULT_APK_DIR,
        help="Gradle release output directory (default: app/build/outputs/apk/release)",
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=DEFAULT_OUTPUT_DIR,
        help="directory for collected APKs (default: dist/release)",
    )
    parser.add_argument(
        "--skip-build",
        action="store_true",
        help="collect existing APKs without invoking Gradle",
    )
    parser.add_argument(
        "--gradle-task",
        default=":app:assembleRelease",
        help="Gradle task to run (default: :app:assembleRelease)",
    )
    parser.add_argument(
        "--gradle-arg",
        action="append",
        default=[],
        help="extra Gradle argument; can be supplied more than once",
    )
    return parser


def _signing_configuration(args: argparse.Namespace) -> tuple[Path, str, str, str] | None:
    if args.unsigned:
        return None

    keystore_value = _resolve_signing_value(
        args.keystore, "BUILD_RELEASE_KEYSTORE", "SIGNING_KEYSTORE"
    )
    alias = _resolve_signing_value(
        args.key_alias, "BUILD_RELEASE_KEY_ALIAS", "SIGNING_KEY_ALIAS"
    )
    store_password = _resolve_signing_value(
        args.store_password, "BUILD_RELEASE_STORE_PASSWORD", "SIGNING_STORE_PASSWORD"
    )
    key_password = _resolve_signing_value(
        args.key_password, "BUILD_RELEASE_KEY_PASSWORD", "KEY_PASSWORD"
    )
    values = (keystore_value, alias, store_password, key_password)
    supplied = any(value is not None for value in values)

    if not supplied and not args.sign:
        return None

    missing = [
        name
        for name, value in zip(
            ("keystore", "key alias", "store password", "key password"), values
        )
        if value is None
    ]
    if missing:
        raise BuildReleaseError(
            "Signing was requested but the following values are missing: "
            + ", ".join(missing)
            + ". Use the signing options or BUILD_RELEASE_* environment variables."
        )

    keystore = _path_from_root(keystore_value)
    if not keystore.is_file():
        raise BuildReleaseError(f"Keystore was not found: {keystore}")
    assert alias is not None
    assert store_password is not None
    assert key_password is not None
    return keystore, alias, store_password, key_password


def main(argv: Sequence[str] | None = None) -> int:
    args = _parser().parse_args(argv)
    try:
        if not args.no_env_file:
            env_file = _path_from_root(args.env_file or DEFAULT_ENV_FILE)
            _load_dotenv(env_file)
        signing = _signing_configuration(args)
        signer = _find_apksigner(args.apksigner) if signing is not None else None

        apk_dir = _path_from_root(args.apk_dir)
        if not args.skip_build:
            _run(
                [*_gradle_command(args.gradle_task), *args.gradle_arg],
                label="Build release",
            )

        sources = _discover_apks(apk_dir)
        output_dir = _path_from_root(args.output_dir)
        output_dir.mkdir(parents=True, exist_ok=True)

        copied: list[Path] = []
        for source in sources:
            destination = _copy_or_sign(
                source,
                output_dir,
                signer=signer,
                keystore=signing[0] if signing else None,
                alias=signing[1] if signing else None,
                store_password=signing[2] if signing else None,
                key_password=signing[3] if signing else None,
            )
            copied.append(destination)

        if signing is None:
            print("Release APKs were built without signing (unsigned mode).")
            print("Use --sign with a release keystore for installable release artifacts.")
        else:
            print("Release APKs were signed and verified.")
        for artifact in copied:
            print(f"  {artifact}  sha256={_sha256(artifact)}")
        return 0
    except (BuildReleaseError, OSError, ValueError) as error:
        print(f"Error: {error}", file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print("\nCancelled.", file=sys.stderr)
        return 130


if __name__ == "__main__":
    raise SystemExit(main())
