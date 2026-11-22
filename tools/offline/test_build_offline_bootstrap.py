#!/usr/bin/env python3
from __future__ import annotations

import tempfile
import unittest
from pathlib import Path
from unittest import mock

from tools.offline import build_offline_bootstrap as bootstrap


def make_package(
    name: str,
    *,
    version: str = "1.0",
    depends: tuple[tuple[tuple[str, str | None, str | None], ...], ...] = (),
    provides: tuple[tuple[str, str | None], ...] = (),
) -> bootstrap.Package:
    return bootstrap.Package(
        name=name,
        version=version,
        architecture="arm64",
        filename=f"pool/{name}_{version}_arm64.deb",
        sha256="0" * 64,
        depends=depends,
        pre_depends=(),
        suite="noble",
        component="main",
        provides=provides,
    )


class RootsTest(unittest.TestCase):
    def test_extra_roots_merge_and_dedupe(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            base = root / "base.txt"
            extra = root / "extra.txt"
            base.write_text("gnome-shell\nshared\n", encoding="utf-8")
            extra.write_text("shared\nextra-application  # app\n", encoding="utf-8")
            with mock.patch.object(bootstrap, "ROOTS_FILE", base):
                self.assertEqual(
                    ["gnome-shell", "shared", "extra-application"],
                    bootstrap.read_roots((extra,)),
                )


class ProvidesResolutionTest(unittest.TestCase):
    def test_parse_provides_preserves_version(self):
        self.assertEqual(
            bootstrap.parse_provides(
                "virtual-api (= 1.5), desktop-bus, default-dbus-session-bus"
            ),
            (
                ("virtual-api", "1.5"),
                ("desktop-bus", None),
                ("default-dbus-session-bus", None),
            ),
        )

    def test_unversioned_provide_does_not_satisfy_versioned_dependency(self):
        provider = make_package(
            "provider",
            provides=(("virtual-api", None),),
        )
        self.assertTrue(
            bootstrap.package_matches_dependency(provider, "virtual-api", None, None)
        )
        self.assertFalse(
            bootstrap.package_matches_dependency(provider, "virtual-api", ">=", "1.0")
        )

    def test_versioned_provide_uses_provided_version(self):
        provider = make_package(
            "provider",
            version="9.0",
            provides=(("virtual-api", "1.5"),),
        )
        self.assertTrue(
            bootstrap.package_matches_dependency(provider, "virtual-api", ">=", "1.0")
        )
        self.assertTrue(
            bootstrap.package_matches_dependency(provider, "virtual-api", "=", "1.5")
        )
        self.assertFalse(
            bootstrap.package_matches_dependency(provider, "virtual-api", ">=", "2.0")
        )

    def test_dconf_depends_on_dbus_session_provider(self):
        dbus_user_session = make_package(
            "dbus-user-session",
            provides=(
                ("dbus-session-bus", None),
                ("default-dbus-session-bus", None),
            ),
        )
        dconf_service = make_package(
            "dconf-service",
            depends=bootstrap.parse_dependencies(
                "default-dbus-session-bus | dbus-session-bus"
            ),
        )
        packages = {
            "dbus-user-session": dbus_user_session,
            "dconf-service": dconf_service,
        }

        self.assertEqual(
            bootstrap.package_dependency_names(dconf_service, packages),
            {"dbus-user-session"},
        )
        batches = bootstrap.dependency_batches(packages)
        batch_names = [{package.name for package in batch} for batch in batches]
        self.assertLess(
            batch_names.index({"dbus-user-session"}),
            batch_names.index({"dconf-service"}),
        )


class InstalledDependencyRepairTest(unittest.TestCase):
    def test_upgrades_installed_package_pinned_to_replaced_dependency(self):
        installed_runtime = make_package("runtime", version="1.0")
        installed_bridge = make_package(
            "bridge",
            version="1.0",
            depends=bootstrap.parse_dependencies("runtime (= 1.0)"),
        )
        root = make_package(
            "tool",
            depends=bootstrap.parse_dependencies("runtime (= 2.0)"),
        )
        repository_runtime = make_package("runtime", version="2.0")
        repository_bridge = make_package(
            "bridge",
            version="2.0",
            depends=bootstrap.parse_dependencies("runtime (= 2.0)"),
        )

        selected, dependencies, unresolved = bootstrap.resolve(
            {"tool": root},
            {"runtime": installed_runtime, "bridge": installed_bridge},
            {
                "tool": [root],
                "runtime": [repository_runtime],
                "bridge": [repository_bridge],
            },
            {},
        )

        self.assertEqual([], unresolved)
        self.assertEqual("2.0", selected["runtime"].version)
        self.assertEqual("2.0", selected["bridge"].version)
        self.assertIn("bridge", dependencies)


if __name__ == "__main__":
    unittest.main()
