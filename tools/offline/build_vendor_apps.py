#!/usr/bin/env python3
"""Build the final, user-application offline overlay for LinuxHub 2.0.

The GNOME archive is the hardware/system layer. This archive contains every
user-facing Ubuntu desktop application plus Chrome and Thunderbird.  Its
installer follows the 1.1.911 model: ``dpkg-deb -x`` overlays application files
onto the already-installed system and never talks to an APT repository.
"""

from __future__ import annotations

import argparse
import gzip
import hashlib
import io
import json
import lzma
import os
import shutil
import sys
import tarfile
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))

from tools.offline import build_offline_bootstrap as bootstrap
OFFLINE = ROOT / "app/src/main/assets/offline"
SYSTEM_LOCK = ROOT / "tools/offline/offline-gnome.lock.json"
APP_ROOTS = ROOT / "tools/offline/ubuntu-24.04-desktop-apps.txt"
VENDOR = OFFLINE / "vendor-apps-arm64.tar"
MANIFEST = OFFLINE / "vendor-packages.txt"
TASKS = OFFLINE / "vendor-app-tasks.tsv"
DEPENDENCIES = OFFLINE / "vendor-dependencies.txt"
LOCK = ROOT / "tools/offline/offline-vendor.lock.json"
INSTALL_SCRIPT = ROOT / "tools/offline/vendor-install.sh"
VENDOR_INPUT = ROOT / ".cache/offline/vendor-input"

# These are the extra command-line and certificate packages needed by Chrome,
# plus the two vendor DEBs that are not part of Ubuntu noble. All are kept in
# the final overlay; the system package archive supplies their normal deps.
VENDOR_PACKAGES = (
    "openssl_3.0.13-0ubuntu3.16_arm64.deb",
    "ca-certificates_20260601~24.04.1_all.deb",
    "fonts-liberation_2.1.5-3_all.deb",
    "wget_1.21.4-1ubuntu4.5_arm64.deb",
    "google-chrome-stable_158.0.8127.36-1_arm64.deb",
    "thunderbird_140.7.1+build1-0ubuntu0.22.04.1_arm64.deb",
    "thunderbird-locale-zh-hans_140.7.1+build1-0ubuntu0.22.04.1_all.deb",
)


# One row is one user-visible application. Keeping the root packages grouped
# prevents LibreOffice or Thunderbird from appearing as several unrelated apps
# in the setup screen.
APP_GROUPS = (
    ("libreoffice", "LibreOffice", (
        "libreoffice",
        "libreoffice-gnome",
        "libreoffice-style-yaru",
        "libreoffice-l10n-zh-cn",
        "libreoffice-help-zh-cn",
    )),
    ("evince", "文档查看器", ("evince",)),
    ("remmina", "Remmina 远程桌面", ("remmina",)),
    ("transmission", "Transmission", ("transmission-gtk",)),
    ("rhythmbox", "Rhythmbox 音乐", ("rhythmbox",)),
    ("totem", "视频播放器", ("totem",)),
    ("shotwell", "Shotwell 照片", ("shotwell",)),
    ("eog", "图像查看器", ("eog",)),
    ("snapshot", "截图工具", ("gnome-snapshot",)),
    ("cheese", "相机", ("cheese",)),
    ("simple-scan", "文档扫描仪", ("simple-scan",)),
    ("calculator", "计算器", ("gnome-calculator",)),
    ("calendar", "日历", ("gnome-calendar",)),
    ("clocks", "时钟", ("gnome-clocks",)),
    ("characters", "字符映射表", ("gnome-characters",)),
    ("contacts", "联系人", ("gnome-contacts",)),
    ("font-viewer", "字体查看器", ("gnome-font-viewer",)),
    ("logs", "日志", ("gnome-logs",)),
    ("maps", "地图", ("gnome-maps",)),
    ("weather", "天气", ("gnome-weather",)),
    ("tweaks", "优化", ("gnome-tweaks",)),
    ("disks", "磁盘工具", ("gnome-disk-utility",)),
    ("system-monitor", "系统监视器", ("gnome-system-monitor",)),
    ("file-roller", "归档管理器", ("file-roller",)),
    ("baobab", "磁盘用量分析器", ("baobab",)),
    ("seahorse", "密码和密钥", ("seahorse",)),
    ("usb-creator", "启动盘创建器", ("usb-creator-gtk",)),
    ("deja-dup", "备份", ("deja-dup",)),
    ("help", "帮助", ("yelp",)),
    ("power-stats", "电源统计", ("gnome-power-manager",)),
    ("remote-desktop", "远程桌面", ("gnome-remote-desktop",)),
    ("orca", "Orca 屏幕阅读器", ("orca",)),
    ("ubuntu-docs", "Ubuntu 文档", ("ubuntu-docs",)),
    ("chrome", "Google Chrome", ("google-chrome-stable",)),
    ("thunderbird", "Thunderbird", (
        "thunderbird",
        "thunderbird-locale-zh-hans",
    )),
)

APPLICATION_VENDOR_ROOTS = {
    "google-chrome-stable",
    "thunderbird",
    "thunderbird-locale-zh-hans",
}


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def read_app_roots() -> list[str]:
    roots: list[str] = []
    for line in APP_ROOTS.read_text(encoding="utf-8").splitlines():
        name = line.split("#", 1)[0].strip()
        if name and name not in roots:
            roots.append(name)
    return roots


def system_packages() -> dict[str, bootstrap.Package]:
    if not SYSTEM_LOCK.is_file():
        raise SystemExit(f"missing system lock: {SYSTEM_LOCK}")
    lock = json.loads(SYSTEM_LOCK.read_text(encoding="utf-8"))
    installed: dict[str, bootstrap.Package] = {}
    for record in lock["packages"]:
        package = bootstrap.Package(
            name=record["package"],
            version=record["version"],
            architecture=record["architecture"],
            filename=record["repository_path"],
            sha256=record["sha256"],
            depends=tuple(
                tuple(tuple(item) for item in alternatives)
                for alternatives in record["depends"]
            ),
            pre_depends=tuple(
                tuple(tuple(item) for item in alternatives)
                for alternatives in record["pre_depends"]
            ),
            suite=record["suite"],
            component=record["component"],
            provides=tuple(tuple(item) for item in record.get("provides", ())),
            source="rootfs",
        )
        installed[package.name] = package
    return installed


def deb_control(path: Path) -> dict[str, str]:
    data = path.read_bytes()
    if not data.startswith(b"!<arch>\n"):
        raise SystemExit(f"not a Debian package: {path}")
    offset = 8
    control_archive: bytes | None = None
    while offset + 60 <= len(data):
        header = data[offset : offset + 60]
        offset += 60
        name = header[:16].decode("ascii", "replace").strip().rstrip("/")
        try:
            size = int(header[48:58].decode("ascii").strip())
        except ValueError as exc:
            raise SystemExit(f"invalid ar member in {path}") from exc
        body = data[offset : offset + size]
        offset += size + (size & 1)
        if name == "control.tar.gz":
            control_archive = gzip.decompress(body)
        elif name == "control.tar.xz":
            control_archive = lzma.decompress(body)
        elif name == "control.tar.zst":
            try:
                import zstandard
            except ImportError as exc:
                raise SystemExit("reading .deb control.tar.zst requires zstandard") from exc
            control_archive = zstandard.ZstdDecompressor().stream_reader(
                io.BytesIO(body)
            ).read()
        elif name == "control.tar":
            control_archive = body
    if control_archive is None:
        raise SystemExit(f"control archive missing: {path}")
    with tarfile.open(fileobj=io.BytesIO(control_archive), mode="r:*") as archive:
        for member in archive.getmembers():
            if member.name.removeprefix("./") == "control":
                stream = archive.extractfile(member)
                if stream is not None:
                    blocks = bootstrap.parse_blocks(stream.read().decode("utf-8"))
                    first = next(iter(blocks), None)
                    if first is not None:
                        return first
    raise SystemExit(f"control file missing: {path}")


def vendor_package(path: Path) -> bootstrap.Package:
    fields = deb_control(path)
    return bootstrap.Package(
        name=fields["Package"],
        version=fields["Version"],
        architecture=fields.get("Architecture", "arm64"),
        filename=str(path),
        sha256=sha256(path),
        depends=bootstrap.parse_dependencies(fields.get("Depends", "")),
        pre_depends=bootstrap.parse_dependencies(fields.get("Pre-Depends", "")),
        suite="vendor",
        component="vendor",
        provides=bootstrap.parse_provides(fields.get("Provides", "")),
        source="vendor",
    )


def find_vendor_input(filename: str) -> Path:
    direct = VENDOR_INPUT / filename
    if direct.is_file():
        return direct
    matches = sorted(VENDOR_INPUT.glob(f"*/{filename}"))
    if len(matches) == 1:
        return matches[0]

    # The command-line/certificate DEBs are downloaded by the system profile.
    # Recover them from an existing offline pool before rebuilding that pool.
    for archive_path in (OFFLINE / "bootstrap-arm64.tar", VENDOR):
        if not archive_path.is_file():
            continue
        with tarfile.open(archive_path, mode="r:*") as archive:
            for member in archive.getmembers():
                if Path(member.name).name != filename:
                    continue
                stream = archive.extractfile(member)
                if stream is None:
                    continue
                VENDOR_INPUT.mkdir(parents=True, exist_ok=True)
                target = VENDOR_INPUT / filename
                temporary = target.with_name(target.name + ".part")
                with temporary.open("wb") as output:
                    shutil.copyfileobj(stream, output)
                os.replace(temporary, target)
                return target
    raise SystemExit(f"missing vendor input: {filename} under {VENDOR_INPUT}")


def package_json(package: bootstrap.Package) -> dict[str, object]:
    return {
        "package": package.name,
        "version": package.version,
        "architecture": package.architecture,
        "sha256": package.sha256,
        "source": package.source,
        "filename": Path(package.filename).name,
        "suite": package.suite,
        "component": package.component,
        "depends": bootstrap.dependency_json(package.depends),
        "pre_depends": bootstrap.dependency_json(package.pre_depends),
        "provides": [list(item) for item in package.provides],
    }


def add_file(
    archive: tarfile.TarFile,
    name: str,
    source: Path,
    *,
    mode: int,
) -> None:
    info = tarfile.TarInfo(name)
    info.size = source.stat().st_size
    info.mode = mode
    info.mtime = 0
    info.uid = info.gid = 0
    info.uname = info.gname = "root"
    with source.open("rb") as stream:
        archive.addfile(info, stream)


def add_text(archive: tarfile.TarFile, name: str, content: str) -> None:
    data = content.encode("utf-8")
    info = tarfile.TarInfo(name)
    info.size = len(data)
    info.mode = 0o644
    info.mtime = 0
    info.uid = info.gid = 0
    info.uname = info.gname = "root"
    archive.addfile(info, io.BytesIO(data))


def write_install_manifests(
    ordered: list[bootstrap.Package],
    application_roots: set[str],
) -> tuple[list[tuple[str, str, list[str]]], int]:
    filenames = {package.name: Path(package.filename).name for package in ordered}
    missing = sorted(
        package_name
        for _, _, package_names in APP_GROUPS
        for package_name in package_names
        if package_name not in filenames
    )
    if missing:
        raise SystemExit(f"missing application packages: {', '.join(missing)}")

    grouped_roots = {
        package_name
        for _, _, package_names in APP_GROUPS
        for package_name in package_names
    }
    if grouped_roots != application_roots:
        extra = sorted(application_roots - grouped_roots)
        absent = sorted(grouped_roots - application_roots)
        raise SystemExit(f"application groups mismatch: extra={extra}, missing={absent}")

    tasks = [
        (key, label, [filenames[package_name] for package_name in package_names])
        for key, label, package_names in APP_GROUPS
    ]
    dependencies = [
        filenames[package.name]
        for package in ordered
        if package.name not in application_roots
    ]
    task_text = "".join(
        f"{key}\t{label}\t{' '.join(package_files)}\n"
        for key, label, package_files in tasks
    )
    TASKS.write_text(task_text, encoding="utf-8")
    DEPENDENCIES.write_text("\n".join(dependencies) + "\n", encoding="utf-8")
    return tasks, len(dependencies)


def write_vendor_archive(
    ordered: list[bootstrap.Package],
    paths: dict[str, Path],
    tasks: list[tuple[str, str, list[str]]],
) -> None:
    temporary = VENDOR.with_name(VENDOR.name + ".part")
    temporary.unlink(missing_ok=True)
    try:
        with temporary.open("wb") as raw:
            with gzip.GzipFile(
                filename="",
                mode="wb",
                fileobj=raw,
                compresslevel=9,
                mtime=0,
            ) as compressed:
                with tarfile.open(
                    fileobj=compressed,
                    mode="w",
                    format=tarfile.GNU_FORMAT,
                ) as archive:
                    for name in (".", "./packages"):
                        info = tarfile.TarInfo(name)
                        info.type = tarfile.DIRTYPE
                        info.mode = 0o755
                        info.mtime = 0
                        info.uid = info.gid = 0
                        info.uname = info.gname = "root"
                        archive.addfile(info)
                    add_file(
                        archive,
                        "./install-vendor.sh",
                        INSTALL_SCRIPT,
                        mode=0o755,
                    )
                    add_text(
                        archive,
                        "./vendor-app-tasks.tsv",
                        TASKS.read_text(encoding="utf-8"),
                    )
                    add_text(
                        archive,
                        "./vendor-dependencies.txt",
                        DEPENDENCIES.read_text(encoding="utf-8"),
                    )
                    for package in ordered:
                        add_file(
                            archive,
                            f"./packages/{paths[package.name].name}",
                            paths[package.name],
                            mode=0o644,
                        )
        os.replace(temporary, VENDOR)
    finally:
        temporary.unlink(missing_ok=True)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--mirror", default=bootstrap.MIRROR)
    parser.add_argument("--fallback-mirror", action="append", default=[])
    parser.add_argument(
        "--skip-assets",
        action="store_true",
        help="resolve and write manifests only; do not rebuild the TAR",
    )
    args = parser.parse_args()
    bootstrap.MIRROR = args.mirror.rstrip("/")
    bootstrap.FALLBACK_MIRRORS = [
        mirror.rstrip("/") for mirror in args.fallback_mirror
    ]

    app_names = read_app_roots()
    by_name, providers = bootstrap.load_indices()
    missing = [name for name in app_names if name not in by_name]
    if missing:
        raise SystemExit(f"missing application metadata: {', '.join(missing)}")
    roots = {name: by_name[name][0] for name in app_names}

    vendor_paths: dict[str, Path] = {}
    for filename in VENDOR_PACKAGES:
        path = find_vendor_input(filename)
        package = vendor_package(path)
        if package.name in roots:
            raise SystemExit(f"duplicate vendor package: {package.name}")
        roots[package.name] = package
        vendor_paths[package.name] = path

    installed = bootstrap.read_rootfs_status()
    installed.update(system_packages())
    selected, dependencies, unresolved = bootstrap.resolve(
        roots, installed, by_name, providers
    )
    if unresolved:
        for parent, alternatives in unresolved:
            print(
                "unresolved: "
                + parent
                + " -> "
                + " | ".join(item[0] for item in alternatives)
            )
        raise SystemExit(2)

    required = set(roots) | dependencies
    packages = {name: selected[name] for name in required}
    ordered = bootstrap.topological_order(packages)
    application_roots = set(app_names) | APPLICATION_VENDOR_ROOTS
    tasks, dependency_count = write_install_manifests(ordered, application_roots)
    names = {package.name: Path(package.filename).name for package in ordered}
    manifest_text = "\n".join(names[package.name] for package in ordered) + "\n"
    manifest_part = MANIFEST.with_name(MANIFEST.name + ".part")
    manifest_part.write_text(manifest_text, encoding="utf-8")
    os.replace(manifest_part, MANIFEST)

    lock = {
        "schema": 1,
        "profile": "vendor-apps-ubuntu-24.04-arm64",
        "system_lock": str(SYSTEM_LOCK.relative_to(ROOT)),
        "application_roots": str(APP_ROOTS.relative_to(ROOT)),
        "application_root_count": len(app_names),
        "vendor_package_count": len(VENDOR_PACKAGES),
        "package_count": len(packages),
        "application_task_count": len(tasks),
        "dependency_package_count": dependency_count,
        "install_strategy": "dpkg-deb-x-after-system",
        "packages": [package_json(package) for package in ordered],
    }
    LOCK.write_text(json.dumps(lock, indent=2, ensure_ascii=False) + "\n")
    print(f"application_roots={len(app_names)}")
    print(f"vendor_packages={len(VENDOR_PACKAGES)}")
    print(f"resolved_packages={len(packages)}")
    print(f"application_tasks={len(tasks)}")
    print(f"dependency_packages={dependency_count}")
    print(f"manifest={MANIFEST.relative_to(ROOT)}")
    if args.skip_assets:
        return

    cached = bootstrap.scan_artifact_cache()
    materialized: dict[str, Path] = {}
    for package in ordered:
        existing = vendor_paths.get(package.name)
        if existing is not None:
            materialized[package.name] = existing
            continue
        materialized[package.name] = bootstrap.materialize(
            package,
            cached.get((package.name, package.version.split(":", 1)[-1])),
        )

    write_vendor_archive(ordered, materialized, tasks)
    print(f"asset={VENDOR.relative_to(ROOT)}")
    print(f"size={VENDOR.stat().st_size}")
    print(f"sha256={sha256(VENDOR)}")


if __name__ == "__main__":
    main()
