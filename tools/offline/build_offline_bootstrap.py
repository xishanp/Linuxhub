#!/usr/bin/env python3
"""Resolve LinuxHub's offline Ubuntu arm64 package pools.

The GNOME profiles contain the hardware and system layer only. User-facing
desktop applications are resolved separately by ``build_vendor_apps.py`` and
installed from the final vendor archive. All package versions and transitive
dependencies come from the pinned Ubuntu arm64 indices and are recorded in a
profile lock file.

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
GNOME24_ROOTFS_ASSET = ROOT / "app/src/main/assets/rootfs/ubuntu-base-24.04.4-base-arm64.tar"
GNOME24_ASSET = OFFLINE_ASSETS / "gnome-24.04-arm64.tar"
GNOME24_LIST = OFFLINE_ASSETS / "gnome-packages.txt"
GNOME24_ROOTS_FILE = ROOT / "tools/offline/package-roots-ubuntu-gnome.txt"
GNOME24_LOCK_FILE = ROOT / "tools/offline/offline-gnome.lock.json"
GNOME_ROOTFS_ASSET = ROOT / "app/src/main/assets/rootfs/ubuntu-base-26.04.1-base-arm64.tar.gz"
GNOME_ASSET = OFFLINE_ASSETS / "gnome-26.04-arm64.tar"
GNOME_LIST = OFFLINE_ASSETS / "gnome-packages-26.04.txt"
GNOME_ROOTS_FILE = ROOT / "tools/offline/package-roots-ubuntu-26.04-gnome.txt"
GNOME_LOCK_FILE = ROOT / "tools/offline/offline-gnome-26.04.lock.json"
CACHE_DIR = ROOT / ".cache/offline"
INDEX_DIR = CACHE_DIR / "indices"
DEB_DIR = CACHE_DIR / "bootstrap-debs"
EXTRACTED_DIR = CACHE_DIR / "bootstrap-packages"

MIRROR = "https://ports.ubuntu.com/ubuntu-ports"
FALLBACK_MIRRORS: list[str] = []
SUITES = ("noble", "noble-updates", "noble-security")
COMPONENTS = ("main", "universe", "restricted", "multiverse")
SUITE_PRIORITY = {"noble-security": 4, "noble-updates": 3, "noble": 2}
COMPONENT_PRIORITY = {"main": 4, "restricted": 3, "universe": 2, "multiverse": 1}
CACHE_LAYERS = (
    "bootstrap-arm64.tar",
    "gnome-24.04-arm64.tar",
    "gnome-26.04-arm64.tar",
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
    provides: tuple[tuple[str, str | None], ...]
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

    relative = None
    if url.startswith(MIRROR + "/"):
        relative = url[len(MIRROR) + 1 :]
    urls = [url]
    if relative:
        urls.extend(f"{mirror}/{relative}" for mirror in FALLBACK_MIRRORS)

    temporary = target.with_name(target.name + ".part")
    errors = []
    for candidate in dict.fromkeys(urls):
        temporary.unlink(missing_ok=True)
        try:
            subprocess.run(
                [
                    "curl",
                    "-fL",
                    "--retry",
                    "3",
                    "--retry-all-errors",
                    "--connect-timeout",
                    "20",
                    "--speed-time",
                    "30",
                    "--speed-limit",
                    "1024",
                    "--user-agent",
                    "LinuxHubOfflineBuilder/2.0",
                    "-o",
                    str(temporary),
                    candidate,
                ],
                check=True,
            )
            temporary.replace(target)
            return
        except subprocess.CalledProcessError as exc:
            errors.append(f"{candidate}: {exc}")
    temporary.unlink(missing_ok=True)
    raise SystemExit("download failed after mirror fallback:\n" + "\n".join(errors))


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


def parse_provides(value: str) -> tuple[tuple[str, str | None], ...]:
    provided = []
    for raw in value.split(","):
        match = re.match(
            r"([A-Za-z0-9+_.:-]+)(?:\s*\(\s*=\s*([^)]+)\))?",
            raw.strip(),
        )
        if match:
            provided.append(
                (match.group(1).split(":", 1)[0], match.group(2))
            )
    return tuple(provided)


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
        provides=parse_provides(fields.get("Provides", "")),
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
        for provided, _ in package.provides:
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


def read_roots(extra_files: tuple[Path, ...] = ()) -> list[str]:
    roots = []
    seen = set()
    for roots_file in (ROOTS_FILE, *extra_files):
        for line in roots_file.read_text().splitlines():
            name = line.split("#", 1)[0].strip()
            if name and name not in seen:
                roots.append(name)
                seen.add(name)
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
            provides=parse_provides(fields.get("Provides", "")),
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


def package_matches_dependency(
    package: Package,
    name: str,
    operator: str | None,
    wanted: str | None,
) -> bool:
    if package.name == name and satisfies(package.version, operator, wanted):
        return True
    for provided, provided_version in package.provides:
        if provided != name:
            continue
        if not operator:
            return True
        if provided_version is not None and satisfies(
            provided_version, operator, wanted
        ):
            return True
    return False


def matching_selected(
    selected: dict[str, Package], name: str, operator: str | None, wanted: str | None
):
    candidates = sorted(selected.values(), key=lambda item: (item.name != name, item.name))
    for candidate in candidates:
        if package_matches_dependency(candidate, name, operator, wanted):
            return candidate
    return None


def matching_installed(
    installed: dict[str, Package], name: str, operator: str | None, wanted: str | None
):
    candidates = sorted(installed.values(), key=lambda item: (item.name != name, item.name))
    for candidate in candidates:
        if package_matches_dependency(candidate, name, operator, wanted):
            return candidate
    return None


def choose_repository(
    alternatives: tuple[tuple[str, str | None, str | None], ...],
    by_name: dict[str, list[Package]],
    providers: dict[str, list[Package]],
):
    for name, operator, wanted in alternatives:
        for candidate in by_name.get(name, []):
            if package_matches_dependency(candidate, name, operator, wanted):
                return candidate
        for candidate in providers.get(name, []):
            if package_matches_dependency(candidate, name, operator, wanted):
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
    repair_dependencies, repair_unresolved = upgrade_inconsistent_installed(
        selected, installed, by_name, providers
    )
    dependencies.update(repair_dependencies)
    unresolved.extend(repair_unresolved)
    return selected, dependencies, unresolved


def dependencies_satisfied(package: Package, state: dict[str, Package]) -> bool:
    for alternatives in package.pre_depends + package.depends:
        if not any(
            package_matches_dependency(candidate, name, operator, wanted)
            for name, operator, wanted in alternatives
            for candidate in state.values()
        ):
            return False
    return True


def upgrade_inconsistent_installed(
    selected: dict[str, Package],
    installed: dict[str, Package],
    by_name: dict[str, list[Package]],
    providers: dict[str, list[Package]],
):
    """Upgrade installed packages invalidated by a selected version change.

    An installed package can have an exact dependency on a package that the
    profile upgrades (for example, libgcc-s1 pins gcc-14-base). Reusing that
    installed package would leave APT in an inconsistent state even though
    dpkg itself audits cleanly.
    """
    dependencies: set[str] = set()
    unresolved: list[tuple[str, tuple[tuple[str, str | None, str | None], ...]]] = []
    while True:
        state = dict(installed)
        state.update(selected)
        inconsistent = [
            package
            for name, package in installed.items()
            if name not in selected and not dependencies_satisfied(package, state)
        ]
        if not inconsistent:
            return dependencies, unresolved

        progressed = False
        for package in sorted(inconsistent, key=lambda item: item.name):
            candidate = choose_repository(
                ((package.name, None, None),), by_name, providers
            )
            if candidate is None:
                unresolved.append(
                    (package.name, ((package.name, None, None),))
                )
                continue
            selected[package.name] = candidate
            dependencies.add(package.name)
            progressed = True
        if not progressed:
            return dependencies, unresolved


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


def package_dependency_names(package: Package, packages: dict[str, Package]) -> set[str]:
    result: set[str] = set()
    for groups in (package.pre_depends, package.depends):
        for alternatives in groups:
            for name, operator, wanted in alternatives:
                dependency = matching_selected(packages, name, operator, wanted)
                if dependency is not None:
                    result.add(dependency.name)
                    break
    return result


def topological_order(packages: dict[str, Package]) -> list[Package]:
    ordered: list[Package] = []
    temporary: set[str] = set()
    permanent: set[str] = set()

    def visit(package: Package) -> None:
        if package.name in permanent or package.name in temporary:
            return
        temporary.add(package.name)
        for dependency_name in sorted(package_dependency_names(package, packages)):
            visit(packages[dependency_name])
        temporary.remove(package.name)
        permanent.add(package.name)
        ordered.append(package)

    for package in sorted(packages.values(), key=lambda value: value.name):
        visit(package)
    return ordered


def strongly_connected_components(packages: dict[str, Package]) -> list[list[str]]:
    graph = {
        name: sorted(package_dependency_names(package, packages))
        for name, package in packages.items()
    }
    indices: dict[str, int] = {}
    lowlinks: dict[str, int] = {}
    stack: list[str] = []
    on_stack: set[str] = set()
    components: list[list[str]] = []
    next_index = 0

    def visit(name: str) -> None:
        nonlocal next_index
        indices[name] = next_index
        lowlinks[name] = next_index
        next_index += 1
        stack.append(name)
        on_stack.add(name)
        for dependency in graph[name]:
            if dependency not in indices:
                visit(dependency)
                lowlinks[name] = min(lowlinks[name], lowlinks[dependency])
            elif dependency in on_stack:
                lowlinks[name] = min(lowlinks[name], indices[dependency])
        if lowlinks[name] == indices[name]:
            component: list[str] = []
            while True:
                member = stack.pop()
                on_stack.remove(member)
                component.append(member)
                if member == name:
                    break
            components.append(sorted(component))

    for name in sorted(packages):
        if name not in indices:
            visit(name)
    return components


def dependency_batches(packages: dict[str, Package]) -> list[list[Package]]:
    """Group packages into installable layers.

    A batch contains no dependency edge between its members, except for a
    strongly connected cycle which must be installed together. Every external
    dependency of a batch is already installed and configured by an earlier
    batch. This makes ``dpkg -i`` safe for Pre-Depends and normal Depends.
    """
    components = strongly_connected_components(packages)
    component_of = {
        name: component_index
        for component_index, component in enumerate(components)
        for name in component
    }
    component_dependencies: list[set[int]] = [set() for _ in components]
    for name, package in packages.items():
        owner = component_of[name]
        for dependency in package_dependency_names(package, packages):
            dependency_owner = component_of[dependency]
            if dependency_owner != owner:
                component_dependencies[owner].add(dependency_owner)

    remaining = set(range(len(components)))
    batches: list[list[Package]] = []
    while remaining:
        ready = [
            index for index in sorted(remaining)
            if not (component_dependencies[index] & remaining)
        ]
        if not ready:
            raise SystemExit("dependency graph contains an unschedulable component cycle")
        names = sorted(
            name
            for index in ready
            for name in components[index]
        )
        batches.append([packages[name] for name in names])
        remaining.difference_update(ready)
    return batches


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
    global BOOTSTRAP_ASSET, BOOTSTRAP_LIST, ROOTS_FILE, LOCK_FILE
    global ROOTFS_ASSET, SUITES, SUITE_PRIORITY, MIRROR, FALLBACK_MIRRORS
    extra_roots_files: tuple[Path, ...] = ()
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--profile",
        choices=("bootstrap", "gnome24", "gnome"),
        default="bootstrap",
        help="select the package profile and its isolated lock/list/tar outputs",
    )
    parser.add_argument("--rebuild-tar", action="store_true")
    parser.add_argument(
        "--mirror",
        default=MIRROR,
        help="Ubuntu ports mirror base URL; package SHA-256 values remain authoritative",
    )
    parser.add_argument(
        "--fallback-mirror",
        action="append",
        default=[],
        help="fallback Ubuntu ports mirror base URL; may be repeated",
    )
    args = parser.parse_args()
    MIRROR = args.mirror.rstrip("/")
    FALLBACK_MIRRORS = [mirror.rstrip("/") for mirror in args.fallback_mirror]

    if args.profile == "gnome24":
        BOOTSTRAP_ASSET = GNOME24_ASSET
        BOOTSTRAP_LIST = GNOME24_LIST
        ROOTS_FILE = GNOME24_ROOTS_FILE
        LOCK_FILE = GNOME24_LOCK_FILE
        ROOTFS_ASSET = GNOME24_ROOTFS_ASSET
        extra_roots_files = ()
    elif args.profile == "gnome":
        BOOTSTRAP_ASSET = GNOME_ASSET
        BOOTSTRAP_LIST = GNOME_LIST
        ROOTS_FILE = GNOME_ROOTS_FILE
        LOCK_FILE = GNOME_LOCK_FILE
        ROOTFS_ASSET = GNOME_ROOTFS_ASSET
        SUITES = ("resolute", "resolute-updates", "resolute-security")
        SUITE_PRIORITY = {
            "resolute-security": 4,
            "resolute-updates": 3,
            "resolute": 2,
        }

    print(f"mirror={MIRROR}")
    if FALLBACK_MIRRORS:
        print(f"fallback_mirrors={','.join(FALLBACK_MIRRORS)}")
    root_names = read_roots(extra_roots_files)
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
                "provides": [list(item) for item in package.provides],
            }
        )
    lock = {
        "schema": 1,
        "profile": args.profile,
        "distribution": "ubuntu",
        "release": "26.04" if args.profile == "gnome" else "24.04",
        "codename": "resolute" if args.profile == "gnome" else "noble",
        "architecture": "arm64",
        "roots_file": str(ROOTS_FILE.relative_to(ROOT)),
        "extra_roots_files": [str(path.relative_to(ROOT)) for path in extra_roots_files],
        "root_count": len(roots),
        "package_count": len(required),
        "packages": records,
    }
    LOCK_FILE.write_text(json.dumps(lock, indent=2, ensure_ascii=False) + "\n")
    packages = {name: selected[name] for name in required}
    ordered = topological_order(packages)
    batches = dependency_batches(packages)
    manifest_names = {
        name: Path(package.filename).name for name, package in packages.items()
    }
    BOOTSTRAP_LIST.write_text(
        "\n\n".join(
            "\n".join(manifest_names[package.name] for package in batch)
            for batch in batches
        ) + "\n"
    )
    print(f"install_batches={len(batches)}")
    if not args.rebuild_tar:
        return

    paths = {
        package.name: materialize(
            package,
            cached.get((package.name, package.version.split(":", 1)[-1]))
        )
        for package in ordered
    }
    write_tar(ordered, paths)
    digest = hashlib.sha256(BOOTSTRAP_ASSET.read_bytes()).hexdigest()
    print(f"asset={BOOTSTRAP_ASSET.relative_to(ROOT)}")
    print(f"size={BOOTSTRAP_ASSET.stat().st_size}")
    print(f"sha256={digest}")
    print(f"packages={len(ordered)}")


if __name__ == "__main__":
    main()
