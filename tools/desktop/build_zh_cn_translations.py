#!/usr/bin/env python3
"""Build LinuxHub's compact Simplified Chinese gettext bundle.

The Ubuntu base image intentionally excludes every gettext catalog to save
space. XFCE ships its Chinese catalogs inside application packages, so those
files must be preserved separately when the dpkg exclusion rule is active.
"""
from __future__ import annotations

import argparse
import hashlib
import io
import shutil
import subprocess
import tarfile
import tempfile
from pathlib import Path, PurePosixPath

ROOT = Path(__file__).resolve().parents[2]
BOOTSTRAP = ROOT / "app/src/main/assets/offline/bootstrap-arm64.tar"
OUTPUT = ROOT / "app/src/main/assets/desktop/zh_CN-translations.tar"
LOCALES = frozenset(("zh_CN", "zh_Hans"))


def run(command: list[str], *, input_bytes: bytes | None = None) -> bytes:
    return subprocess.run(command, input=input_bytes, check=True, stdout=subprocess.PIPE).stdout


def deb_payload(deb: Path) -> bytes:
    members = run(["ar", "t", str(deb)]).decode().splitlines()
    member = next(name for name in members if name.startswith("data.tar."))
    payload = run(["ar", "p", str(deb), member])
    suffix = member.removeprefix("data.tar")
    decompressor = {".zst": "zstd", ".xz": "xz", ".gz": "gzip"}.get(suffix)
    if not decompressor:
        raise SystemExit(f"unsupported package compression: {member}")
    command = [decompressor, "-dc"]
    return run(command, input_bytes=payload)


def catalog_path(member_name: str) -> Path | None:
    parts = PurePosixPath(member_name.removeprefix("./")).parts
    if len(parts) < 6:
        return None
    if parts[0:3] != ("usr", "share", "locale") or parts[3] not in LOCALES:
        return None
    if parts[4] != "LC_MESSAGES" or not parts[-1].endswith(".mo"):
        return None
    if any(part in ("", ".", "..") for part in parts):
        return None
    return Path(*parts)


def extract_catalogs(deb: Path, destination: Path) -> None:
    with tarfile.open(fileobj=io.BytesIO(deb_payload(deb))) as package:
        for member in package:
            relative = catalog_path(member.name)
            if relative is None or not member.isfile():
                continue
            source = package.extractfile(member)
            if source is None:
                continue
            target = destination / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            with target.open("wb") as output:
                shutil.copyfileobj(source, output)


def build(output: Path) -> None:
    with tempfile.TemporaryDirectory(prefix="linuxhub-zh-cn-") as temp:
        temp_path = Path(temp)
        catalogs = temp_path / "catalogs"
        catalogs.mkdir()
        package_dir = temp_path / "packages"
        package_dir.mkdir()
        with tarfile.open(BOOTSTRAP) as archive:
            for member in archive:
                if not member.isfile() or not member.name.endswith(".deb"):
                    continue
                source = archive.extractfile(member)
                if source is None:
                    continue
                package = package_dir / Path(member.name).name
                with package.open("wb") as target:
                    shutil.copyfileobj(source, target)
                extract_catalogs(package, catalogs)
                package.unlink()

        files = sorted(path for path in catalogs.rglob("*") if path.is_file())
        if not files:
            raise SystemExit("no Simplified Chinese catalogs found")
        output.parent.mkdir(parents=True, exist_ok=True)
        temporary = output.with_suffix(output.suffix + ".part")
        with tarfile.open(temporary, "w", format=tarfile.GNU_FORMAT) as archive:
            for path in files:
                relative = path.relative_to(catalogs).as_posix()
                info = archive.gettarinfo(str(path), arcname=relative)
                info.uid = info.gid = 0
                info.uname = info.gname = "root"
                info.mtime = 0
                info.mode = 0o644
                with path.open("rb") as source:
                    archive.addfile(info, source)
        temporary.replace(output)
        digest = hashlib.sha256(output.read_bytes()).hexdigest()
        print(f"catalogs={len(files)}")
        print(f"size={output.stat().st_size}")
        print(f"sha256={digest}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, default=OUTPUT)
    args = parser.parse_args()
    build(args.output)


if __name__ == "__main__":
    main()
