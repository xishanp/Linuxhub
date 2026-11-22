#!/usr/bin/env python3
"""Recolor WhiteSur XFWM window chrome to LinuxHub's graphite material."""
from __future__ import annotations

import io
import tarfile
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
ARCHIVE = ROOT / "app/src/main/assets/desktop/whitesur-gtk.tar"
PREFIX = "themes/WhiteSur-Dark/xfwm4/"
GRAPHITE = (43, 46, 51)
BACKGROUND_PREFIXES = (
    "title-",
    "top-left-",
    "top-right-",
    "left-",
    "right-",
    "bottom-",
    "bottom-left-",
    "bottom-right-",
)


def recolor(data: bytes) -> bytes:
    image = Image.open(io.BytesIO(data)).convert("RGBA")
    pixels = []
    for red, green, blue, alpha in image.getdata():
        if alpha == 0:
            pixels.append((0, 0, 0, 0))
        else:
            # Xfwm4 tiles these decoration pixmaps directly. Keep their source
            # alpha for antialiased corners; whole-frame translucency belongs to
            # the compositor (frame_opacity), not to every titlebar tile.
            pixels.append((*GRAPHITE, alpha))
    image.putdata(pixels)
    output = io.BytesIO()
    image.save(output, format="PNG", optimize=True)
    return output.getvalue()


def rewrite_themerc(data: bytes) -> bytes:
    text = data.decode("utf-8")
    replacements = {
        "active_text_color=#afafaf": "active_text_color=#f2f2f2",
        "inactive_text_color=#808080": "inactive_text_color=#c7c7c7",
        "active_text_shadow_color=#252525": "active_text_shadow_color=#2b2e33",
        "inactive_text_shadow_color=#252525": "inactive_text_shadow_color=#2b2e33",
    }
    for old, new in replacements.items():
        text = text.replace(old, new)
    return text.encode("utf-8")


def main() -> None:
    temporary = ARCHIVE.with_suffix(ARCHIVE.suffix + ".part")
    with tarfile.open(ARCHIVE, "r:gz") as source, tarfile.open(
        temporary, "w:gz", format=tarfile.GNU_FORMAT, compresslevel=9
    ) as target:
        for member in source:
            data = None
            if member.isfile():
                extracted = source.extractfile(member)
                data = extracted.read() if extracted else b""
                short_name = member.name.removeprefix(PREFIX)
                if member.name.startswith(PREFIX + "themerc"):
                    data = rewrite_themerc(data)
                elif (
                    member.name.startswith(PREFIX)
                    and short_name.startswith(BACKGROUND_PREFIXES)
                    and short_name.endswith(".png")
                ):
                    data = recolor(data)
            info = tarfile.TarInfo(member.name)
            info.type = member.type
            info.mode = member.mode
            info.uid = info.gid = 0
            info.uname = info.gname = "root"
            info.mtime = 0
            info.linkname = member.linkname
            info.size = len(data) if data is not None else 0
            target.addfile(info, io.BytesIO(data) if data is not None else None)
    temporary.replace(ARCHIVE)
    import hashlib
    print(hashlib.sha256(ARCHIVE.read_bytes()).hexdigest())
    print(ARCHIVE.stat().st_size)


if __name__ == "__main__":
    main()
