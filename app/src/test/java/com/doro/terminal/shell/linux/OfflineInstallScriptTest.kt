package com.doro.terminal.shell.linux

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineInstallScriptTest {
    @Test
    fun failsWhenManifestIsMissingOrEmpty() {
        val script = OfflineInstallScript.build("bootstrap-packages.txt")
        assertTrue(script.contains("manifest=/var/cache/doro-offline/bootstrap-packages.txt"))
        assertTrue(script.contains("test -s \"\$manifest\""))
    }

    @Test
    fun unpacksBeforeConfiguringAllPackages() {
        val script = OfflineInstallScript.build("bootstrap-packages.txt")
        assertTrue(script.contains("xargs -a \"\$manifest\" dpkg \$dpkg_opts --unpack"))
        assertTrue(script.indexOf("--unpack") < script.indexOf("--configure -a"))
    }

    @Test
    fun neverUsesOnlinePackageRepair() {
        val script = OfflineInstallScript.build("bootstrap-packages.txt")
        assertFalse(script.contains("apt-get"))
        assertFalse(script.contains("|| true"))
    }

    @Test
    fun suppressesAllConfigurationPrompts() {
        val script = OfflineInstallScript.build("bootstrap-packages.txt")
        assertTrue(script.contains("DEBIAN_FRONTEND=noninteractive"))
        assertTrue(script.contains("--force-confdef --force-confold"))
        assertTrue(script.contains("UCF_FORCE_CONFFOLD=1"))
    }

    @Test
    fun auditsTheFinalDpkgState() {
        val script = OfflineInstallScript.build("bootstrap-packages.txt")
        assertTrue(script.contains("dpkg -C"))
        assertTrue(script.indexOf("--configure -a") < script.indexOf("dpkg -C"))
    }
}
