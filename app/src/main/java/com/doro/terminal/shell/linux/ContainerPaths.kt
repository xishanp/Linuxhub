package com.doro.terminal.shell.linux

import android.content.Context
import java.io.File

class ContainerPaths(context: Context) {
    val baseDir = File(context.filesDir, "linux")
    val runtimeDir = File(baseDir, "runtime")
    val rootfsDir = File(baseDir, "distributions/ubuntu-24.04/rootfs")
    val configFile = File(baseDir, "container.properties")
    val initializationStateFile = File(baseDir, "initialization.properties")
    val initializationLogFile = File(baseDir, "logs/initialization.log")
    val rootfsReadyMarker = File(rootfsDir, ".doro-rootfs-ready")
    val offlinePackagesDir = File(rootfsDir, "var/cache/doro-offline")
    val bootstrapReadyMarker = File(rootfsDir, ".doro-bootstrap-ready")
    val sudoReadyMarker = File(rootfsDir, ".doro-sudo-ready-v2")
    val gnomeReadyMarker = File(rootfsDir, ".doro-gnome-46-ready")
    val vendorAppsReadyMarker = File(rootfsDir, ".doro-vendor-apps-ready")
    val vendorRuntimeRepairMarker = File(rootfsDir, ".doro-vendor-runtime-repair-ready")
    val desktopInstallerAssetsMarker = File(rootfsDir, ".doro-deb-installer-assets-ready")
    val userDirectoriesMarker = File(rootfsDir, ".doro-user-directories-ready")
    val fastfetchReadyMarker = File(rootfsDir, ".doro-fastfetch-ready")
    val trustedCertificatesReadyMarker = File(rootfsDir, ".doro-ca-certificates-ready")
    val mesaGpuReadyMarker = File(rootfsDir, ".doro-mesa-gpu-27.0.0-devel-20261025-r2-ready")
    val desktopProfileMarker = File(rootfsDir, "var/lib/doro/desktop-profile.version")

    /**
     * The guest's audio dropbox.
     *
     * proot reached it through `-b <app>/tmp/audio:/run/doro-audio`; Husk makes
     * `/run/doro-audio` a real directory *inside* the rootfs, so both sides use
     * one path and the host bridge reads the same FIFO the guest writes.
     */
    val audioDir = File(rootfsDir, "run/doro-audio")
    val audioPipe = File(audioDir, "pulse-output.pcm")
}
