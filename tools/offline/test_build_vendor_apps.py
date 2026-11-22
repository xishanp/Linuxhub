#!/usr/bin/env python3
from __future__ import annotations

import unittest
from pathlib import Path

from tools.offline import build_vendor_apps as vendor


class AppRootsTest(unittest.TestCase):
    def test_desktop_manifest_is_the_vendor_application_source(self):
        roots = vendor.read_app_roots()
        self.assertEqual(37, len(roots))
        self.assertEqual(len(roots), len(set(roots)))
        for package in (
            "libreoffice",
            "evince",
            "rhythmbox",
            "totem",
            "shotwell",
            "gnome-disk-utility",
            "gnome-system-monitor",
            "file-roller",
        ):
            self.assertIn(package, roots)


class VendorMetadataTest(unittest.TestCase):
    def test_chrome_and_thunderbird_are_real_offline_debs(self):
        chrome = vendor.find_vendor_input(
            "google-chrome-stable_158.0.8127.36-1_arm64.deb"
        )
        thunderbird = vendor.find_vendor_input(
            "thunderbird_140.7.1+build1-0ubuntu0.22.04.1_arm64.deb"
        )
        self.assertEqual("google-chrome-stable", vendor.deb_control(chrome)["Package"])
        self.assertEqual("thunderbird", vendor.deb_control(thunderbird)["Package"])

    def test_manifest_has_no_apt_or_dpkg_install_script(self):
        script = vendor.INSTALL_SCRIPT.read_text(encoding="utf-8")
        self.assertIn('dpkg-deb --fsys-tarfile "$deb"', script)
        self.assertIn("--keep-directory-symlink", script)
        self.assertIn("/etc/ca-certificates.conf", script)
        self.assertIn("update-ca-certificates --fresh", script)
        self.assertNotIn("apt-get", script)
        self.assertNotIn("dpkg -i", script)
        self.assertNotIn("dpkg --configure", script)

    def test_application_tasks_are_one_visible_group_per_app(self):
        tasks = vendor.TASKS.read_text(encoding="utf-8").splitlines()
        dependencies = vendor.DEPENDENCIES.read_text(encoding="utf-8").splitlines()
        self.assertEqual(35, len(tasks))
        self.assertEqual(221, len(dependencies))
        self.assertTrue(any(line.startswith("libreoffice\tLibreOffice\t") for line in tasks))
        self.assertTrue(any(line.startswith("chrome\tGoogle Chrome\t") for line in tasks))
        self.assertTrue(any(line.startswith("thunderbird\tThunderbird\t") for line in tasks))


if __name__ == "__main__":
    unittest.main()
