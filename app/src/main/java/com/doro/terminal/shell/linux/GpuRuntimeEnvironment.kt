package com.doro.terminal.shell.linux

/**
 * Rendering is policy-driven. Mutter must use llvmpipe on PRoot: Zink can
 * create valid client window pixmaps while failing to sample them into the
 * Shell's actor tree, which makes otherwise-running applications invisible.
 * The selected hardware mode is therefore a capability/app-launch marker, not
 * a renderer override for GNOME Shell itself.
 *
 * Legacy 1.x XFCE helpers deliberately keep their software-only behavior.
 */
internal object GpuRuntimeEnvironment {
    const val MODE_FILE = "/etc/doro-gpu-mode"
    private fun legacySoftwareExports(): String =
        "unset MESA_LOADER_DRIVER_OVERRIDE VK_ICD_FILENAMES VK_DRIVER_FILES " +
            "MESA_VK_WSI_PRESENT_MODE FD_KGSL_ENABLE_DMABUF VTEST_SOCKET " +
            "LIBGL_ALWAYS_INDIRECT LIBGL_DRI3_DISABLE; " +
            "export GALLIUM_DRIVER=llvmpipe LIBGL_ALWAYS_SOFTWARE=1; "

    private fun gnomeSoftwareExports(): String =
        "unset VK_ICD_FILENAMES VK_DRIVER_FILES " +
            "MESA_VK_WSI_PRESENT_MODE FD_KGSL_ENABLE_DMABUF VTEST_SOCKET " +
            "LIBGL_ALWAYS_INDIRECT LIBGL_DRI3_DISABLE ZINK_DESCRIPTORS TU_DEBUG; " +
            "export MESA_LOADER_DRIVER_OVERRIDE=swrast GALLIUM_DRIVER=llvmpipe LIBGL_ALWAYS_SOFTWARE=1; " +
            "export GJS_DISABLE_JIT=1; "

    /** Legacy 1.x path: keep desktop helpers on the safe software renderer. */
    fun sessionShell(mode: GpuRenderMode): String =
        "doro_gpu_mode=software; doro_gpu_capability=${mode.id}; " + legacySoftwareExports()

    /**
     * GNOME 2.0 path: Mutter stays on safe software composition in every mode.
     * Applications launched through doro-gpu-run independently opt into Zink
     * (Qualcomm) or virpipe/VirGL (MediaTek).
     */
    fun gnomeSessionShell(mode: GpuRenderMode): String =
        "doro_gpu_mode=software; doro_gpu_capability=${mode.id}; " + gnomeSoftwareExports()

    fun profileScript(): String =
        "case \$- in *i*) ;; *) return 0 ;; esac\n" +
            "[ \"\${DORO_GPU_RUNTIME:-}\" = \"1\" ] && return 0\n" +
            legacySoftwareExports().trimEnd().replace("; ", "\n").replace(";", "") + "\n"

    /**
     * The executable launcher lives in desktop/profile/doro-gpu-run.sh so its
     * shell behavior and tiered Zink/KGSL or virpipe/VirGL -> software fallback
     * are testable.
     */
    val applicationLauncherAsset = "desktop/profile/doro-gpu-run.sh"
}
