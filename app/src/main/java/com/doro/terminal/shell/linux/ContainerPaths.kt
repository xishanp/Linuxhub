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
    val vendorAppsReadyMarker = File(rootfsDir, ".doro-vendor-apps-ready")
    val sparkStoreReadyMarker = File(rootfsDir, ".doro-spark-store-ready")
    val fastfetchReadyMarker = File(rootfsDir, ".doro-fastfetch-ready")
    val mesaGpuReadyMarker = File(rootfsDir, ".doro-mesa-gpu-26.2-isolated-v2-ready")
    val prootTmpDir = File(baseDir, "tmp/proot")
    val sharedMemoryDir = File(baseDir, "tmp/shm")
    val audioDir = File(baseDir, "tmp/audio")
    val audioPipe = File(audioDir, "pulse-output.pcm")
    val compatibilityProcDir = File(baseDir, "tmp/proc")
    val compatibilityProcStat = File(compatibilityProcDir, "stat")
    val compatibilityPowerSupplyDir = File(baseDir, "tmp/power_supply")
    val proot = File(nativeLibraryDir, "libproot.so")
    val loader = File(nativeLibraryDir, "libproot-loader.so")
    val talloc = File(runtimeDir, "libtalloc.so.2")
    val androidShmem = File(runtimeDir, "libandroid-shmem.so")
    val getifaddrsServer = File(nativeLibraryDir, "libexec_getifaddrs_bridge_server.so")
    val getifaddrsClientAsset = "runtime/arm64-v8a/libgetifaddrs_bridge.so"
    val getifaddrsSocket = File(rootfsDir, "tmp/.getifaddrs-bridge")
    val x11SocketDir = File(baseDir, "tmp/.X11-unix")
    val libraryDir = File(runtimeDir, "lib")
}