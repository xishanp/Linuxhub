package com.doro.terminal.shell.linux

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VendorRuntimeRepairTest {
    @Test
    fun repairsChromeIconAndLibreOfficeRegistryWithoutDpkgMaintainerScripts() {
        val script = VendorRuntimeRepair.script()
        assertTrue(script.contains("product_logo_"))
        assertTrue(script.contains("google-chrome.png"))
        assertTrue(script.contains("sed -i"))
        assertTrue(script.contains("/etc/libreoffice/registry"))
        assertTrue(script.contains("libreoffice/share/.registry"))
        assertTrue(script.contains("libreoffice.conf"))
        assertFalse(script.contains("dpkg -i"))
    }
}
