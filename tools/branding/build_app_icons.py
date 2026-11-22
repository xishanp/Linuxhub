#!/usr/bin/env python3
"""Install the supplied launcher artwork without modifying its pixels.

The launcher image is copied byte-for-byte into a density-independent mipmap.
It is never cropped, resized, recoloured, recentred, or background-removed.
"""

from __future__ import annotations

import argparse
import hashlib
import shutil
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
DEFAULT_SOURCE = ROOT / "reference/branding/linuxhub-latest.jpg"
DEFAULT_RES = ROOT / "app/src/main/res"
OUTPUT_PATH = Path("mipmap-nodpi/ic_launcher_linuxhub.jpg")


def build(source_path: Path, res_dir: Path) -> Path:
    destination = res_dir / OUTPUT_PATH
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source_path, destination)
    if destination.read_bytes() != source_path.read_bytes():
        raise RuntimeError("launcher artwork copy is not byte-identical")
    return destination


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, default=DEFAULT_SOURCE)
    parser.add_argument("--res-dir", type=Path, default=DEFAULT_RES)
    args = parser.parse_args()
    output = build(args.source, args.res_dir)
    digest = hashlib.sha256(output.read_bytes()).hexdigest()
    print(f"wrote={output.relative_to(ROOT)}")
    print(f"bytes={output.stat().st_size}")
    print(f"sha256={digest}")


if __name__ == "__main__":
    main()
