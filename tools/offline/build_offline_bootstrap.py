#!/usr/bin/env python3
"""Resolve and build LinuxHub's single offline Ubuntu arm64 package pool.

`package-roots.txt` is the only manually maintained package list. All package
versions and transitive dependencies come from the pinned Ubuntu 24.04 arm64
indices and are recorded in `offline-bootstrap.lock.json`.

The generated TAR is an artifact cache plus the exact dpkg install manifest.
Existing offline TARs are read only as a download cache: a cached DEB is reused
only when its package name and version exactly match the resolved lock.
"""
from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import re
import shutil
import subprocess
import tarfile
import urllib.parse
from dataclasses import dataclass
from pathlib import Path

from debian.debian_support import Version

ROOT = Path(__file__).resolve().parents[2]
OFFLINE_ASSETS = ROOT / "app/src/main/assets/offline"
ROOTFS_ASSET = ROOT / "app/src/main/assets/rootfs/ubuntu-base-24.04.4-base-arm64.tar"
BOOTSTRAP_ASSET = OFFLINE_ASSETS / "bootstrap-arm64.tar"
BOOTSTRAP_LIST = OFFLINE_ASSETS / "bootstrap-packages.txt"
ROOTS_FILE = ROOT / "tools/offline/package-roots.txt"
LOCK_FILE = ROOT / "tools/offline/offline-bootstrap.lock.json"
CACHE_DIR = ROOT / ".cache/offline"
INDEX_DIR = CACHE_DIR / "indices"
DEB_DIR = CACHE_DIR / "bootstrap-debs"
EXTRACTED_DIR = CACHE_DIR / "bootstrap-packages"

MIRROR = "https://ports.ubuntu.com/ubuntu-ports"
SUITES = ("noble", "noble-updates", "noble-security")
COMPONENTS = ("main", "universe", "restricted", "multiverse")
SUITE_PRIORITY = {"noble-security": 4, "noble-updates": 3, "noble": 2}
COMPONENT_PRIORITY = {"main": 4, "restricted": 3, "universe": 2, "multiverse": 1}
CACHE_LAYERS = (
    "bootstrap-arm64.tar",
    "system-base-arm64.tar",
    "system-tools-arm64.tar",
    "cli-extra-arm64.tar",
    "spark-store-deps-arm64.tar",
    "gpu-tools-arm64.tar",
    "vendor-apps-arm64.tar",
)


@dataclass(frozen=True)
class Package:
    name: str
    version: str
    architecture: str
    filename: str
    sha256: str
    depends: tuple[tuple[tuple[str, str | None, str | None], ...], ...]
    pre_depends: tuple[tuple[tuple[str, str | None, str | None], ...], ...]
    suite: str
    component: str
    provides: tuple[str, ...]
    source: str = "repository"

    @property
    def basename(self) -> str:
        return Path(self.filename).name

    @property
    def candidate_priority(self) -> tuple[Version, int, int]:
        # Archive priority must never hide a newer package version. Ubuntu can
        # publish a lower version in -security while -updates has the new one.
        return (
            Version(self.version),
            SUITE_PRIORITY.get(self.suite, 0),
            COMPONENT_PRIORITY.get(self.component, 0),
        )


@dataclass(frozen=True)
class Location:
    archive: Path
    member: str


def download(url: str, target: Path) -> None:
    target.parent.mkdir(parents=True, exist_ok=True)
    if target.is_file() and target.stat().st_size > 0:
        return
    temporary = target.with_name(target.name + ".part")
    subprocess.run(
        [
            "curl",
            "-fL",
            "--retry",
            "5",
            "--retry-all-errors",
            "--connect-timeout",
            "20",
            "--speed-time",
            "30",
            "--speed-limit",
            "1024",
            "-o",
            str(temporary),
            url,
        ],
        check=True,
    )
    temporary.replace(target)


def parse_blocks(text: str):
    for block in text.split("\n\n"):
        fields: dict[str, str] = {}
        last: str | None = None
        for line in block.splitlines():
            if not line:
                continue
            if line[0] in " \t":
                if last:
                    fields[last] += "\n" + line.strip()
            elif ":" in line:
                key, value = line.split(":", 1)
                fields[key] = value.strip()
                last = key
        if fields:
            yield fields


def parse_dependencies(value: str):
    groups = []
    for raw_group in value.split(","):
        alternatives = []
        for raw in raw_group.split("|"):
            match = re.match(
                r"([A-Za-z0-9+_.:-]+)(?:\s*\((<<|<=|=|>=|>>)\s*([^)]+)\))?",
                raw.strip(),
            )
            if match:
                alternatives.append(
                    (match.group(1).split(":", 1)[0], match.group(2), match.group(3))
                )
        if alternatives:
            groups.append(tuple(alternatives))
    return tuple(groups)


def package_from_fields(fields: dict[str, str], *, suite: str, component: str) -> Package:
    name = fields.get("Package")
    version = fields.get("Version")
    filename = fields.get("Filename")
    sha256 = fields.get("SHA256")
    if not name or not version or not filename or not sha256:
        raise SystemExit("invalid package index record")
    return Package(
        name=name,
        version=version,
        architecture=fields.get("Architecture", "arm64"),
        filename=filename,
        sha256=sha256,
        depends=parse_dependencies(fields.get("Depends", "")),
        pre_depends=parse_dependencies(fields.get("Pre-Depends", "")),
        suite=suite,
        component=component,
        provides=tuple(
            item.strip().split()[0].split(":", 1)[0]
            for item in fields.get("Provides", "").split(",")
            if item.strip()
        ),
    )


def load_indices():
    packages: list[Package] = []
    for suite in SUITES:
        for component in COMPONENTS:
            target = INDEX_DIR / f"{suite}-{component}.gz"
            url = f"{MIRROR}/dists/{suite}/{component}/binary-arm64/Packages.gz"
            try:
                download(url, target)
            except Exception as exc:
                if component == "main":
                    raise
                print(f"warning: skip {suite}/{component}: {exc}")
                continue
            with gzip.open(target, "rt", errors="replace") as source:
                for fields in parse_blocks(source.read()):
                    if fields.get("Architecture") not in ("arm64", "all"):
                        continue
                    packages.append(
                        package_from_fields(fields, suite=suite, component=component)
                    )
    by_name: dict[str, list[Package]] = {}
    providers: dict[str, list[Package]] = {}
    for package in packages:
        by_name.setdefault(package.name, []).append(package)
        for provided in package.provides:
            providers.setdefault(provided, []).append(package)
    for values in list(by_name.values()) + list(providers.values()):
        values.sort(key=lambda item: item.candidate_priority, reverse=True)
    return by_name, providers


def deb_parts(basename: str):
    decoded = urllib.parse.unquote(basename)
    if not decoded.endswith(".deb"):
        return None
    parts = decoded[:-4].split("_")
    if len(parts) < 3:
        return None
    return parts[0].split(":", 1)[0], parts[1].split(":", 1)[-1]


def read_roots() -> list[str]:
    roots = []
    for line in ROOTS_FILE.read_text().splitlines():
        name = line.split("#", 1)[0].strip()
        if name:
            roots.append(name)
    return roots


def scan_artifact_cache():
    by_version: dict[tuple[str, str], Location] = {}
    for layer_name in CACHE_LAYERS:
        archive = OFFLINE_ASSETS / layer_name
        if not archive.is_file():
            continue
        with tarfile.open(archive) as source:
            for member in source.getmembers():
                if not member.isfile() or not member.name.endswith(".deb"):
                    continue
                parts = deb_parts(Path(member.name).name)
                if parts:
                    by_version.setdefault(parts, Location(archive, member.name))
    return by_version


def read_rootfs_status() -> dict[str, Package]:
    status = subprocess.check_output(
        ["tar", "-xOf", str(ROOTFS_ASSET), "./var/lib/dpkg/status"], text=True
    )
    installed = {}
    for fields in parse_blocks(status):
        if not fields.get("Status", "").endswith(" installed"):
            continue
        name = fields.get("Package")
        version = fields.get("Version")
        if not name or not version:
            continue
        installed[name] = Package(
            name=name,
            version=version,
            architecture=fields.get("Architecture", "arm64"),
            filename="",
            sha256="",
            depends=parse_dependencies(fields.get("Depends", "")),
            pre_depends=parse_dependencies(fields.get("Pre-Depends", "")),
            suite="rootfs",
            component="rootfs",
            provides=tuple(
                item.strip().split()[0].split(":", 1)[0]
                for item in fields.get("Provides", "").split(",")
                if item.strip()
            ),
            source="rootfs",
        )
    return installed


def satisfies(version: str, operator: str | None, wanted: str | None) -> bool:
    if not operator:
        return True
    left, right = Version(version), Version(wanted or "0")
    return {
        "<<": left < right,
        "<=": left <= right,
        "=": left == right,
        ">=": left >= right,
        ">>": left > right,
    }[operator]


def matching_selected(
    selected: dict[str, Package], name: str, operator: str | None, wanted: str | None
):
    package = selected.get(name)
    if package and satisfies(package.version, operator, wanted):
        return package
    for candidate in selected.values():
        if name in candidate.provides and satisfies(candidate.version, operator, wanted):
            return candidate
    return None


def matching_installed(
    installed: dict[str, Package], name: str, operator: str | None, wanted: str | None
):
    package = installed.get(name)
    if package and satisfies(package.version, operator, wanted):
        return package
    for candidate in installed.values():
        if name in candidate.provides and satisfies(candidate.version, operator, wanted):
            return candidate
    return None


def choose_repository(
    alternatives: tuple[tuple[str, str | None, str | None], ...],
    by_name: dict[str, list[Package]],
    providers: dict[str, list[Package]],
):
    for name, operator, wanted in alternatives:
        for candidate in by_name.get(name) or providers.get(name, []):
            if satisfies(candidate.version, operator, wanted):
                return candidate
    return None


def resolve(
    roots: dict[str, Package],
    installed: dict[str, Package],
    by_name: dict[str, list[Package]],
    providers: dict[str, list[Package]],
):
    selected = dict(roots)
    dependencies: set[str] = set()
    queue = sorted(selected)
    visited: set[str] = set()
    unresolved = []
    while queue:
        name = queue.pop(0)
        if name in visited:
            continue
        visited.add(name)
        package = selected[name]
        for alternatives in package.pre_depends + package.depends:
            chosen = None
            for dependency, operator, wanted in alternatives:
                chosen = matching_selected(selected, dependency, operator, wanted)
                if chosen:
                    break
            if not chosen:
                for dependency, operator, wanted in alternatives:
                    chosen = matching_installed(installed, dependency, operator, wanted)
                    if chosen:
                        break
            if not chosen:
                chosen = choose_repository(alternatives, by_name, providers)
            if not chosen:
                unresolved.append((name, alternatives))
            elif chosen.source != "rootfs" and chosen.name not in selected:
                selected[chosen.name] = chosen
                dependencies.add(chosen.name)
                queue.append(chosen.name)
    return selected, dependencies, unresolved


def read_member(location: Location) -> bytes:
    with tarfile.open(location.archive) as source:
        extracted = source.extractfile(location.member)
        if extracted is None:
            raise SystemExit(f"missing archive member: {location.archive}:{location.member}")
        return extracted.read()


def materialize(
    package: Package,
    cached: Location | None,
) -> Path:
    DEB_DIR.mkdir(parents=True, exist_ok=True)
    target = DEB_DIR / package.basename
    if target.is_file():
        digest = hashlib.sha256(target.read_bytes()).hexdigest()
        if digest == package.sha256:
            return target
    if cached is not None:
        target.write_bytes(read_member(cached))
        digest = hashlib.sha256(target.read_bytes()).hexdigest()
        if digest == package.sha256:
            return target
    if target.exists():
        target.unlink()
    download(f"{MIRROR}/{package.filename}", target)
    digest = hashlib.sha256(target.read_bytes()).hexdigest()
    if digest != package.sha256:
        raise SystemExit(f"sha256 mismatch {package.name}: {digest} != {package.sha256}")
    return target


def topological_order(packages: dict[str, Package]) -> list[Package]:
    ordered: list[Package] = []
    temporary: set[str] = set()
    permanent: set[str] = set()

    def visit(package: Package) -> None:
        if package.name in permanent or package.name in temporary:
            return
        temporary.add(package.name)
        for alternatives in package.pre_depends + package.depends:
            for name, operator, wanted in alternatives:
                dependency = packages.get(name)
                if dependency and satisfies(dependency.version, operator, wanted):
                    visit(dependency)
                    break
        temporary.remove(package.name)
        permanent.add(package.name)
        ordered.append(package)

    for package in sorted(packages.values(), key=lambda value: value.name):
        visit(package)
    return ordered


def write_tar(packages: list[Package], paths: dict[str, Path]) -> None:
    temporary = BOOTSTRAP_ASSET.with_name(BOOTSTRAP_ASSET.name + ".part")
    with tarfile.open(temporary, "w", format=tarfile.GNU_FORMAT) as archive:
        for name, kind in ((".", tarfile.DIRTYPE), ("./packages", tarfile.DIRTYPE)):
            info = tarfile.TarInfo(name)
            info.type = kind
            info.mode = 0o755
            info.mtime = 0
            info.uid = info.gid = 0
            info.uname = info.gname = "root"
            archive.addfile(info)
        for package in packages:
            path = paths[package.name]
            info = tarfile.TarInfo(f"./packages/{path.name}")
            info.size = path.stat().st_size
            info.mode = 0o644
            info.mtime = 0
            info.uid = info.gid = 0
            info.uname = info.gname = "root"
            with path.open("rb") as source:
                archive.addfile(info, source)
    temporary.replace(BOOTSTRAP_ASSET)


def dependency_json(groups):
    return [
        [[name, operator, wanted] for name, operator, wanted in alternatives]
        for alternatives in groups
    ]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--rebuild-tar", action="store_true")
    args = parser.parse_args()

    root_names = read_roots()
    by_name, providers = load_indices()
    missing_metadata = [name for name in root_names if name not in by_name]
    if missing_metadata:
        raise SystemExit(f"missing package metadata: {', '.join(missing_metadata)}")
    roots = {name: by_name[name][0] for name in root_names}
    installed = read_rootfs_status()
    selected, dependencies, unresolved = resolve(roots, installed, by_name, providers)
    if unresolved:
        for parent, alternatives in unresolved:
            print(f"unresolved: {parent} -> {' | '.join(item[0] for item in alternatives)}")
        raise SystemExit(2)

    required = set(roots) | dependencies
    cached = scan_artifact_cache()
    cache_key = lambda name: (name, selected[name].version.split(":", 1)[-1])
    reused = sum(cache_key(name) in cached for name in required)
    downloads = len(required) - reused
    print(f"roots={len(roots)}")
    print(f"resolved_nodes={len(selected)}")
    print(f"bootstrap_packages={len(required)}")
    print(f"reused_artifacts={reused} downloads={downloads}")

    records = []
    for name in sorted(required):
        package = selected[name]
        records.append(
            {
                "package": package.name,
                "version": package.version,
                "architecture": package.architecture,
                "sha256": package.sha256,
                "repository_path": package.filename,
                "suite": package.suite,
                "component": package.component,
                "depends": dependency_json(package.depends),
                "pre_depends": dependency_json(package.pre_depends),
            }
        )
    lock = {
        "schema": 1,
        "distribution": "ubuntu",
        "release": "24.04",
        "architecture": "arm64",
        "roots_file": str(ROOTS_FILE.relative_to(ROOT)),
        "root_count": len(roots),
        "package_count": len(required),
        "packages": records,
    }
    LOCK_FILE.write_text(json.dumps(lock, indent=2, ensure_ascii=False) + "\n")
    if not args.rebuild_tar:
        return

    packages = {name: selected[name] for name in required}
    ordered = topological_order(packages)
    paths = {
        package.name: materialize(
            package,
            cached.get((package.name, package.version.split(":", 1)[-1]))
        )
        for package in ordered
    }
    write_tar(ordered, paths)
    BOOTSTRAP_LIST.write_text("\n".join(paths[item.name].name for item in ordered) + "\n")
    digest = hashlib.sha256(BOOTSTRAP_ASSET.read_bytes()).hexdigest()
    print(f"asset={BOOTSTRAP_ASSET.relative_to(ROOT)}")
    print(f"size={BOOTSTRAP_ASSET.stat().st_size}")
    print(f"sha256={digest}")
    print(f"packages={len(ordered)}")


if __name__ == "__main__":
    main()
