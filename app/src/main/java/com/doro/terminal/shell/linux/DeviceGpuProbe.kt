package com.doro.terminal.shell.linux

import android.os.Build
import java.io.File

internal enum class GpuRenderMode(val id: String) {
    ZINK("zink"),
    KGSL("kgsl"),
    VIRGL("virgl"),
    SOFTWARE("software");

    val isHardware: Boolean
        get() = this != SOFTWARE

    fun toProfile(): GpuProfile = when (this) {
        ZINK, KGSL -> GpuProfile.QUALCOMM
        VIRGL -> GpuProfile.MEDIATEK
        SOFTWARE -> GpuProfile.SOFTWARE
    }

    companion object {
        val DEFAULT = SOFTWARE

        fun fromId(value: String?): GpuRenderMode =
            entries.firstOrNull { it.id == value?.trim()?.lowercase() } ?: DEFAULT
    }
}

/**
 * Selects a renderer only when a complete local driver path is present.
 *
 * Qualcomm applications use Mesa Zink -> Turnip -> KGSL/Adreno. MediaTek
 * applications use Mesa virpipe -> VTEST -> the host VirGL server -> the
 * vendor EGL/Mali driver. GNOME Shell intentionally stays on llvmpipe; these
 * modes are application launch capabilities, not a Mutter compositor override.
 */
internal object DeviceGpuProbe {
    private const val KGSL_DEVICE = "/dev/kgsl-3d0"
    private const val KGSL_DRI = "usr/lib/aarch64-linux-gnu/dri/kgsl_dri.so"
    private const val TURNIP_DRIVER = "usr/lib/aarch64-linux-gnu/libvulkan_freedreno.so"
    private const val TURNIP_ICD = "usr/share/vulkan/icd.d/freedreno_icd.aarch64.json"
    private val virglGuestDrivers = listOf(
        "usr/lib/aarch64-linux-gnu/dri/virtio_gpu_dri.so",
        "usr/lib/aarch64-linux-gnu/dri/virpipe_dri.so",
    )

    fun select(
        gpuIdentity: String,
        hasKgslDevice: Boolean,
        hasDriverStack: Boolean,
        hasVirglGuestStack: Boolean = false,
    ): GpuRenderMode = when {
        isQualcomm(gpuIdentity) && hasKgslDevice && hasDriverStack -> GpuRenderMode.ZINK
        isMediaTek(gpuIdentity) && hasVirglGuestStack -> GpuRenderMode.VIRGL
        else -> GpuRenderMode.SOFTWARE
    }

    fun resolve(profile: GpuProfile, rootfsDir: File): GpuRenderMode {
        val detected = detect(rootfsDir)
        return when (profile) {
            GpuProfile.AUTO -> detected
            GpuProfile.QUALCOMM -> detected.takeIf { it == GpuRenderMode.ZINK } ?: GpuRenderMode.SOFTWARE
            GpuProfile.MEDIATEK -> detected.takeIf { it == GpuRenderMode.VIRGL } ?: GpuRenderMode.SOFTWARE
            GpuProfile.SOFTWARE -> GpuRenderMode.SOFTWARE
        }
    }

    fun detect(rootfsDir: File): GpuRenderMode {
        val identity = hardwareIdentity()
        val driverStack = listOf(KGSL_DRI, TURNIP_DRIVER, TURNIP_ICD)
            .all { File(rootfsDir, it).isFile }
        // /dev/kgsl-3d0 is a character device. File.isFile() only accepts regular
        // files, so using it here made every Adreno device look inaccessible.
        val kgslDevice = isUsableKgslDevice(File(KGSL_DEVICE))
        val virglGuest = hasVirglGuestDriver(rootfsDir)
        return select(identity, kgslDevice, driverStack, virglGuest)
    }

    fun hardwareIdentity(): String = buildList {
        add(readProperty("ro.hardware.egl"))
        add(readProperty("ro.hardware.vulkan"))
        add(readProperty("ro.soc.manufacturer"))
        add(readProperty("ro.soc.model"))
        add(Build.HARDWARE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Build.SOC_MANUFACTURER)
            add(Build.SOC_MODEL)
        }
    }.filter(String::isNotBlank).joinToString(" ")

    fun hasVirglGuestDriver(rootfsDir: File): Boolean =
        virglGuestDrivers.any { File(rootfsDir, it).isFile }

    internal fun isUsableKgslDevice(file: File): Boolean =
        file.exists() && file.canRead() && file.canWrite()

    private fun isQualcomm(identity: String): Boolean =
        identity.contains("adreno", ignoreCase = true) ||
            identity.contains("qualcomm", ignoreCase = true) ||
            identity.contains("qti", ignoreCase = true)

    private fun isMediaTek(identity: String): Boolean =
        identity.contains("mediatek", ignoreCase = true) ||
            identity.contains("dimensity", ignoreCase = true) ||
            identity.contains("mtk", ignoreCase = true) ||
            (identity.contains("mali", ignoreCase = true) &&
                identity.contains("mt", ignoreCase = true))

    private fun readProperty(name: String): String = runCatching {
        ProcessBuilder("getprop", name).start().let { process ->
            val value = process.inputStream.bufferedReader().use { it.readText().trim() }
            process.waitFor()
            value
        }
    }.getOrDefault("")
}
