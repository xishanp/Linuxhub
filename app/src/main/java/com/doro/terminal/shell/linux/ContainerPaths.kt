package com.doro.terminal.shell.linux

import android.content.Context
import java.io.File

class ContainerPaths(context: Context) {
    val nativeLibraryDir = File(context.applicationInfo.nativeLibraryDir)
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
    val prootTmpDir = File(baseDir, "tmp/proot")
    val sharedMemoryDir = File(baseDir, "tmp/shm")
    val audioDir = File(baseDir, "tmp/audio")
    val desktopScreenshotDir = File(baseDir, "tmp/desktop-screenshots")
    val audioPipe = File(audioDir, "pulse-output.pcm")
    val compatibilityProcDir = File(baseDir, "tmp/proc")
    val compatibilityProcStat = File(compatibilityProcDir, "stat")
    val compatibilityProcVersion = File(compatibilityProcDir, "version")
    val compatibilityPciDevices = File(compatibilityProcDir, "pci-devices")
    val compatibilityPowerSupplyDir = File(baseDir, "tmp/power_supply")
    val proot = File(nativeLibraryDir, "libproot.so")
    val loader = File(nativeLibraryDir, "libproot-loader.so")
    val getifaddrsServer = File(nativeLibraryDir, "libexec_getifaddrs_bridge_server.so")
    val getifaddrsClientAsset = "runtime/arm64-v8a/libgetifaddrs_bridge.so"
    val getifaddrsSocket = File(rootfsDir, "tmp/.getifaddrs-bridge")
    val x11SocketDir = File(baseDir, "tmp/.X11-unix")
    // PRoot resolves both dependencies through LD_LIBRARY_PATH=libraryDir.
    // Keep the versioned names here so DT_NEEDED entries resolve exactly.
    val libraryDir = File(runtimeDir, "lib")
    val talloc = File(libraryDir, "libtalloc.so.2")
    val androidShmem = File(libraryDir, "libandroid-shmem.so")
}
