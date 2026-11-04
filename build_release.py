#!/usr/bin/env python3
"""Build the Android release APKs and/or AAB and optionally sign them.

The current Gradle configuration does not assign a signing config to the
``release`` build type, so Gradle produces ``*-release-unsigned.apk`` files and
an unsigned ``app-release.aab``. This script keeps signing outside Gradle,
which also matches the GitHub Actions release workflow.

APKs are signed with ``apksigner``. Android App Bundles (AAB), which are
uploaded to Google Play, cannot be signed with apksigner and are instead signed
with the JDK ``jarsigner`` tool (JAR signing scheme).

Examples::

    # Build and collect both APKs and the AAB. Sign them when signing
    # variables are present, otherwise keep them unsigned.
    python build_release.py

    # Build and sign both APKs and the AAB using the keystore from .env.
    python build_release.py --sign

    # Only build and sign the AAB for Google Play.
    python build_release.py --artifact aab --sign

    # Only process artifacts that were built already.
    python build_release.py --skip-build --unsigned

For unattended local use, the signing options can also be supplied through
``BUILD_RELEASE_*`` variables. Use ``BUILD_RELEASE_KEYSTORE`` for a local
keystore path. The older ``SIGNING_KEYSTORE``, ``SIGNING_KEY_ALIAS``,
``SIGNING_STORE_PASSWORD`` and ``KEY_PASSWORD`` names are accepted as aliases.

The signing tools are discovered automatically. Use ``APKSIGNER`` /
``JARSIGNER`` to point at specific executables, or ``JAVA_HOME`` to use the
binaries of a particular JDK. When those are unset, the Android Studio bundled
JBR and the Android SDK / PATH are searched as fallbacks.

The repository-root ``.env`` file is loaded automatically. Values already
present in the process environment take precedence. Use ``--env-file`` to
load another file, or ``--no-env-file`` to disable dotenv loading.
"""

from __future__ import annotations

import argparse
import hashlib
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path
from typing import Sequence


ROOT = Path(__file__).resolve().parent
DEFAULT_APK_DIR = ROOT / "app" / "build" / "outputs" / "apk" / "release"
DEFAULT_AAB_DIR = ROOT / "app" / "build" / "outputs" / "bundle" / "release"
DEFAULT_OUTPUT_DIR = ROOT / "dist" / "release"
DEFAULT_ENV_FILE = ROOT / ".env"


class BuildReleaseError(RuntimeError):
    """An expected error while building, signing, or collecting artifacts."""


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


def _gradle_command(tasks: Sequence[str]) -> list[str]:
    wrapper_name = "gradlew.bat" if os.name == "nt" else "gradlew"
    wrapper = ROOT / wrapper_name
    if not wrapper.is_file():
        raise BuildReleaseError(f"Gradle wrapper not found: {wrapper}")
    return [str(wrapper), *tasks]


def _gradle_tasks(artifact: str, override: str | None) -> list[str]:
    """Pick the Gradle task(s) for the requested artifact type."""

    if override:
        return [override]
    if artifact == "apk":
        return [":app:assembleRelease"]
    if artifact == "aab":
        return [":app:bundleRelease"]
    return [":app:assembleRelease", ":app:bundleRelease"]


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


def _find_jarsigner(explicit_path: str | None) -> Path:
    """Locate the JDK jarsigner used to sign Android App Bundles."""

    if explicit_path:
        signer = _path_from_root(explicit_path)
        if signer.is_file():
            return signer
        raise BuildReleaseError(f"jarsigner was not found: {signer}")

    from_environment = _non_empty_environment("JARSIGNER")
    if from_environment:
        signer = _path_from_root(from_environment)
        if signer.is_file():
            return signer
        raise BuildReleaseError(f"jarsigner was not found: {signer}")

    exe_name = "jarsigner.exe" if os.name == "nt" else "jarsigner"

    java_home = _non_empty_environment("JAVA_HOME")
    if java_home:
        candidate = Path(java_home).expanduser() / "bin" / exe_name
        if candidate.is_file():
            return candidate

    candidates: list[Path] = []
    if os.name == "nt":
        program_files = _non_empty_environment("ProgramFiles", "ProgramW6432")
        local_app_data = _non_empty_environment("LOCALAPPDATA")
        if program_files:
            pf = Path(program_files)
            android_dir = pf / "Android"
            if android_dir.is_dir():
                candidates.extend(
                    android_dir.glob(f"Android Studio*/jbr/bin/{exe_name}")
                )
            for jdk_parent in (
                "Java",
                "Eclipse Adoptium",
                "Microsoft",
                "Amazon Corretto",
                "BellSoft",
            ):
                base = pf / jdk_parent
                if base.is_dir():
                    candidates.extend(base.glob(f"*/bin/{exe_name}"))
        if local_app_data:
            lad = Path(local_app_data)
            android_dir = lad / "Android"
            if android_dir.is_dir():
                candidates.extend(
                    android_dir.glob(f"Android Studio*/jbr/bin/{exe_name}")
                )
            programs = lad / "Programs"
            if programs.is_dir():
                candidates.extend(programs.glob(f"*/bin/{exe_name}"))
    elif sys.platform == "darwin":
        candidates.append(
            Path("/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/jarsigner")
        )
        jvm_dir = Path("/Library/Java/JavaVirtualMachines")
        if jvm_dir.is_dir():
            candidates.extend(jvm_dir.glob("*/Contents/Home/bin/jarsigner"))
    else:
        candidates.append(Path("/opt/android-studio/jbr/bin/jarsigner"))
        candidates.append(Path.home() / "android-studio" / "jbr" / "bin" / "jarsigner")
        jvm_dir = Path("/usr/lib/jvm")
        if jvm_dir.is_dir():
            candidates.extend(jvm_dir.glob("*/bin/jarsigner"))

    for candidate in candidates:
        if candidate.is_file():
            return candidate

    executable = shutil.which("jarsigner") or shutil.which("jarsigner.exe")
    if executable:
        return Path(executable)

    raise BuildReleaseError(
        "Could not locate jarsigner. Set JARSIGNER or JAVA_HOME, install a JDK, "
        "or use the Android Studio bundled JBR."
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


def _discover_aabs(aab_dir: Path) -> list[Path]:
    if not aab_dir.is_dir():
        raise BuildReleaseError(f"Release AAB directory does not exist: {aab_dir}")

    bundles = sorted(
        {
            *aab_dir.glob("app-*-release.aab"),
            *aab_dir.glob("app-release.aab"),
        }
    )
    if bundles:
        return bundles

    raise BuildReleaseError(f"No release AABs found in {aab_dir}")


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


def _sign_aab(
    signer: Path,
    source: Path,
    destination: Path,
    *,
    keystore: Path,
    alias: str,
    store_password: str,
    key_password: str,
) -> None:
    # App Bundles use JAR signing via jarsigner. Passwords are passed through
    # child-process environment variables instead of the command line.
    child_environment = os.environ.copy()
    child_environment["BUILD_RELEASE_STORE_PASSWORD"] = store_password
    child_environment["BUILD_RELEASE_KEY_PASSWORD"] = key_password
    command = [
        str(signer),
        "-keystore",
        str(keystore),
        "-storepass:env",
        "BUILD_RELEASE_STORE_PASSWORD",
        "-keypass:env",
        "BUILD_RELEASE_KEY_PASSWORD",
        "-digestalg",
        "SHA-256",
        "-signedjar",
        str(destination),
        str(source),
        alias,
    ]
    _run(command, label=f"Sign {source.name}", env=child_environment)

    _run([str(signer), "-verify", str(destination)], label=f"Verify {destination.name}")


def _process_apk(
    source: Path,
    output_dir: Path,
    *,
    signer: Path | None,
    signing: tuple[Path, str, str, str] | None,
) -> Path:
    should_sign = signer is not None
    destination_name = _signed_name(source) if should_sign else source.name
    destination = output_dir / destination_name

    if should_sign and source.name.endswith("-unsigned.apk"):
        assert signing is not None
        keystore, alias, store_password, key_password = signing
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


def _process_aab(
    source: Path,
    output_dir: Path,
    *,
    signer: Path | None,
    signing: tuple[Path, str, str, str] | None,
) -> Path:
    destination = output_dir / source.name

    if signer is not None:
        assert signing is not None
        keystore, alias, store_password, key_password = signing
        _sign_aab(
            signer,
            source,
            destination,
            keystore=keystore,
            alias=alias,
            store_password=store_password,
            key_password=key_password,
        )
    elif source.resolve() != destination.resolve():
        shutil.copy2(source, destination)

    return destination


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--artifact",
        choices=("apk", "aab", "both"),
        default="both",
        help="release artifact(s) to build and collect (default: both)",
    )
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
        help="require a keystore and sign the generated artifacts",
    )
    signing_mode.add_argument(
        "--unsigned",
        action="store_true",
        help="do not sign artifacts, even if signing variables are present",
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
        "--jarsigner",
        help="path to jarsigner (or JARSIGNER); discovered from JDK/JBR by default",
    )
    parser.add_argument(
        "--apk-dir",
        type=Path,
        default=DEFAULT_APK_DIR,
        help="Gradle release APK output directory (default: app/build/outputs/apk/release)",
    )
    parser.add_argument(
        "--aab-dir",
        type=Path,
        default=DEFAULT_AAB_DIR,
        help="Gradle release AAB output directory (default: app/build/outputs/bundle/release)",
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=DEFAULT_OUTPUT_DIR,
        help="directory for collected artifacts (default: dist/release)",
    )
    parser.add_argument(
        "--skip-build",
        action="store_true",
        help="collect existing artifacts without invoking Gradle",
    )
    parser.add_argument(
        "--gradle-task",
        help="override the Gradle task (default: assembleRelease and/or bundleRelease)",
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

        want_apk = args.artifact in ("apk", "both")
        want_aab = args.artifact in ("aab", "both")
        apk_signer = (
            _find_apksigner(args.apksigner)
            if signing is not None and want_apk
            else None
        )
        aab_signer = (
            _find_jarsigner(args.jarsigner)
            if signing is not None and want_aab
            else None
        )

        if not args.skip_build:
            tasks = _gradle_tasks(args.artifact, args.gradle_task)
            _run(
                [*_gradle_command(tasks), *args.gradle_arg],
                label="Build release",
            )

        output_dir = _path_from_root(args.output_dir)
        output_dir.mkdir(parents=True, exist_ok=True)

        collected: list[Path] = []
        if want_apk:
            apk_dir = _path_from_root(args.apk_dir)
            for source in _discover_apks(apk_dir):
                collected.append(
                    _process_apk(
                        source,
                        output_dir,
                        signer=apk_signer,
                        signing=signing,
                    )
                )
        if want_aab:
            aab_dir = _path_from_root(args.aab_dir)
            for source in _discover_aabs(aab_dir):
                collected.append(
                    _process_aab(
                        source,
                        output_dir,
                        signer=aab_signer,
                        signing=signing,
                    )
                )

        if signing is None:
            print("Release artifacts were built without signing (unsigned mode).")
            print("Use --sign with a release keystore for installable/Google Play artifacts.")
        else:
            print("Release artifacts were signed and verified.")
        for artifact in collected:
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
