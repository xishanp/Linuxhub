package com.doro.terminal.shell.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VendorAppsCheckScriptTest {
    @Test
    fun verifiesEveryBundledUserApplicationWithoutDpkgRegistration() {
        val script = VendorAppsCheckScript.check()
        listOf(
            "/opt/google/chrome/chrome",
            "/usr/bin/thunderbird",
            "/usr/lib/libreoffice/program/soffice.bin",
            "/usr/bin/evince",
            "/usr/bin/rhythmbox",
            "/usr/bin/totem",
            "/usr/bin/shotwell",
            "/usr/bin/gnome-disks",
            "/usr/bin/gnome-system-monitor",
            "/usr/bin/file-roller",
            "org.gnome.Evince.desktop",
            "org.gnome.Rhythmbox3.desktop",
            "org.gnome.Shotwell.desktop",
            "langpack-zh-CN@thunderbird.mozilla.org.xpi",
            "check_missing",
            "LD_LIBRARY_PATH=/opt/google/chrome ldd",
            "dpkg -C",
        ).forEach { expected ->
            assertTrue("missing check: $expected", script.contains(expected))
        }
        assertFalse(script.contains("dpkg-query -W"))
        assertFalse(script.contains("google-chrome-stable"))
        assertFalse(script.contains("thunderbird-locale-zh-hans"))
    }

    @Test
    fun generatedChecksAreValidShell() {
        val process = ProcessBuilder(
            "/bin/sh",
            "-n",
            "-c",
            VendorAppsCheckScript.check(),
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(output, 0, process.waitFor())
    }

    @Test
    fun startupCheckKeepsVendorChecksButSkipsSystemRepairs() {
        val script = VendorAppsCheckScript.check(includeSystemChecks = false)
        assertTrue(script.contains("check_missing"))
        assertTrue(script.contains("/usr/bin/thunderbird"))
        assertFalse(script.contains("/etc/ssl/certs/ca-certificates.crt"))
        assertFalse(script.contains("dpkg -C"))
    }
}
