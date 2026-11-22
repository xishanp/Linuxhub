package com.doro.terminal.shell.linux

/**
 * Identifies the installed vendor overlay. The schema is part of the marker
 * so an APK that changes migration logic can re-run the overlay once even when
 * the packaged vendor archive itself did not change.
 */
internal object VendorAppsUpgradePolicy {
    private const val OVERLAY_SCHEMA = 2
    private const val RUNTIME_REPAIR_SCHEMA = 2

    fun marker(archiveSha256: String): String {
        require(archiveSha256.matches(Regex("[0-9a-f]{64}"))) { "vendor 包哈希格式无效" }
        return "vendor-apps-overlay-v$OVERLAY_SCHEMA-$archiveSha256"
    }

    fun runtimeRepairMarker(archiveSha256: String): String {
        require(archiveSha256.matches(Regex("[0-9a-f]{64}"))) { "vendor 包哈希格式无效" }
        return "vendor-runtime-repair-v$RUNTIME_REPAIR_SCHEMA-$archiveSha256"
    }

    fun requiresInstall(installedMarker: String?, archiveSha256: String): Boolean =
        installedMarker?.trim() != marker(archiveSha256)

    fun requiresRuntimeRepair(installedMarker: String?, archiveSha256: String): Boolean =
        installedMarker?.trim() != runtimeRepairMarker(archiveSha256)
}
