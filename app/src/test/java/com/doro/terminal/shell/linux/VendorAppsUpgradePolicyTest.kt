package com.doro.terminal.shell.linux

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VendorAppsUpgradePolicyTest {
    private val archiveSha = "3c4146e38ccb799d76cc30b21e03a30a8f6cf5b5bdef9bcae5f1fd6eb09e4517"

    @Test
    fun currentOverlayMarkerSkipsReinstallation() {
        val marker = VendorAppsUpgradePolicy.marker(archiveSha)

        assertFalse(VendorAppsUpgradePolicy.requiresInstall("$marker\n", archiveSha))
    }

    @Test
    fun legacyMarkerMigratesTheExistingContainerOnce() {
        val legacy = "vendor-apps-all-desktop-$archiveSha"

        assertTrue(VendorAppsUpgradePolicy.requiresInstall(legacy, archiveSha))
    }

    @Test
    fun changedArchiveForcesAnOverlayUpdate() {
        val current = VendorAppsUpgradePolicy.marker(archiveSha)
        val changedSha = "a".repeat(64)

        assertTrue(VendorAppsUpgradePolicy.requiresInstall(current, changedSha))
    }

    @Test
    fun runtimeRepairIsCachedByBothSchemaAndVendorContent() {
        val current = VendorAppsUpgradePolicy.runtimeRepairMarker(archiveSha)

        assertFalse(VendorAppsUpgradePolicy.requiresRuntimeRepair(current, archiveSha))
        assertTrue(VendorAppsUpgradePolicy.requiresRuntimeRepair(null, archiveSha))
        assertTrue(VendorAppsUpgradePolicy.requiresRuntimeRepair(current, "b".repeat(64)))
    }
}
