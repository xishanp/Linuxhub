#!/usr/bin/env python3
"""Build the LinuxHub icon override layer from checked-in SVG sources.

Tela Circle is the complete, runnable icon theme. This archive overlays only
LinuxHub-owned icons directly into Tela Circle instead of creating an
incomplete inheritance theme that GTK and Plank can fail to resolve.
"""

from __future__ import annotations

import argparse
import base64
import gzip
import hashlib
import io
from pathlib import Path
import tarfile


SOURCE_THEME = "LinuxHub-Pixel"
TARGET_THEME = "Tela-circle"
DEFAULT_OUTPUT = Path("app/src/main/assets/desktop/linuxhub-icons.bin")
OVERRIDES = Path(__file__).resolve().parent / "overrides" / SOURCE_THEME
ROOT = Path(__file__).resolve().parents[2]
BRAND_SOURCE = ROOT / "reference/branding/linuxhub-latest.jpg"

ICON_ALIASES = {
    "scalable/places/inode-directory.svg": "scalable/places/folder.svg",
    "scalable/mimetypes/text-plain.svg": "scalable/mimetypes/text-x-generic.svg",
    "scalable/mimetypes/text-markdown.svg": "scalable/mimetypes/text-x-generic.svg",
    "scalable/mimetypes/text-x-script.svg": "scalable/mimetypes/text-x-generic.svg",
    "scalable/mimetypes/application-zip.svg": "scalable/mimetypes/application-x-archive.svg",
    "scalable/mimetypes/application-x-tar.svg": "scalable/mimetypes/application-x-archive.svg",
    "scalable/mimetypes/application-x-compressed-tar.svg": "scalable/mimetypes/application-x-archive.svg",
    "scalable/mimetypes/application-gzip.svg": "scalable/mimetypes/application-x-archive.svg",
    "scalable/mimetypes/package-x-generic.svg": "scalable/mimetypes/application-x-archive.svg",
    "scalable/mimetypes/image-jpeg.svg": "scalable/mimetypes/image-x-generic.svg",
    "scalable/mimetypes/image-png.svg": "scalable/mimetypes/image-x-generic.svg",
    "scalable/mimetypes/audio-x-mpeg.svg": "scalable/mimetypes/audio-x-generic.svg",
    "scalable/mimetypes/audio-x-wav.svg": "scalable/mimetypes/audio-x-generic.svg",
    "scalable/mimetypes/video-mp4.svg": "scalable/mimetypes/video-x-generic.svg",
    "scalable/mimetypes/video-x-mpeg.svg": "scalable/mimetypes/video-x-generic.svg",
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    return parser.parse_args()


def add_bytes(archive: tarfile.TarFile, name: str, data: bytes, mode: int = 0o644) -> None:
    info = tarfile.TarInfo(name)
    info.size = len(data)
    info.mode = mode
    info.mtime = 0
    info.uid = info.gid = 0
    info.uname = info.gname = "root"
    archive.addfile(info, io.BytesIO(data))


def add_directory(archive: tarfile.TarFile, name: str) -> None:
    info = tarfile.TarInfo(name.rstrip("/") + "/")
    info.type = tarfile.DIRTYPE
    info.mode = 0o755
    info.mtime = 0
    info.uid = info.gid = 0
    info.uname = info.gname = "root"
    archive.addfile(info)


def branded_start_here() -> bytes:
    payload = base64.b64encode(BRAND_SOURCE.read_bytes()).decode("ascii")
    return (
        '<svg xmlns="http://www.w3.org/2000/svg" '
        'xmlns:xlink="http://www.w3.org/1999/xlink" '
        'width="1080" height="1080" viewBox="0 0 1080 1080">\n'
        '  <image width="1080" height="1080" '
        f'xlink:href="data:image/jpeg;base64,{payload}"/>\n'
        '</svg>\n'
    ).encode("utf-8")


def build(output: Path) -> None:
    files: dict[str, bytes] = {}
    for path in sorted(OVERRIDES.rglob("*")):
        if not path.is_file():
            continue
        relative = path.relative_to(OVERRIDES).as_posix()
        if relative.startswith("scalable/"):
            files[f"icons/{TARGET_THEME}/{relative}"] = path.read_bytes()

    # The LinuxHub launcher mark is the supplied artwork embedded without pixel
    # changes. SVG is only a scalable container required by the GTK icon theme.
    files[f"icons/{TARGET_THEME}/scalable/apps/start-here.svg"] = branded_start_here()

    for alias, source in ICON_ALIASES.items():
        files[f"icons/{TARGET_THEME}/{alias}"] = files[f"icons/{TARGET_THEME}/{source}"]

    # Tela Circle's panel and fixed-size directories are authoritative for
    # XFCE and Plank. Overlay the same branded SVG into those lookup paths so
    # panel-sized requests do not fall back to an unrelated stock icon.
    panel_aliases = {
        "16/panel/start-here.svg": "scalable/apps/start-here.svg",
        "22/panel/start-here.svg": "scalable/apps/start-here.svg",
        "24/panel/start-here.svg": "scalable/apps/start-here.svg",
        "32/status/start-here.svg": "scalable/apps/start-here.svg",
        "16/places/folder.svg": "scalable/places/folder.svg",
        "22/places/folder.svg": "scalable/places/folder.svg",
        "24/places/folder.svg": "scalable/places/folder.svg",
        "16/places/user-home.svg": "scalable/places/user-home.svg",
        "22/places/user-home.svg": "scalable/places/user-home.svg",
        "24/places/user-home.svg": "scalable/places/user-home.svg",
        "symbolic/status/avatar-default-symbolic.svg": "scalable/status/avatar-default-symbolic.svg",
    }
    for alias, source in panel_aliases.items():
        target = f"icons/{TARGET_THEME}/{alias}"
        files[target] = files[f"icons/{TARGET_THEME}/{source}"]

    output.parent.mkdir(parents=True, exist_ok=True)
    tar_buffer = io.BytesIO()
    with tarfile.open(fileobj=tar_buffer, mode="w", format=tarfile.GNU_FORMAT) as archive:
        directories = sorted(
            {
                "/".join(name.split("/")[:index])
                for name in files
                for index in range(1, len(name.split("/")))
            }
        )
        for directory in directories:
            add_directory(archive, directory)
        for name in sorted(files):
            add_bytes(archive, name, files[name])

    # Keep the asset hash stable across rebuilds and across machines.
    with output.open("wb") as raw:
        with gzip.GzipFile(filename="", mode="wb", fileobj=raw, compresslevel=9, mtime=0) as archive:
            archive.write(tar_buffer.getvalue())

    digest = hashlib.sha256(output.read_bytes()).hexdigest()
    print(f"wrote={output}")
    print(f"bytes={output.stat().st_size}")
    print(f"sha256={digest}")
    print(f"entries={len(files)}")


def main() -> None:
    build(parse_args().output)


if __name__ == "__main__":
    main()
