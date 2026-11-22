package com.doro.terminal.shell.linux

/**
 * JNI boundary for LinuxHub's Rust-owned GNOME session control plane.
 *
 * The native library is the primary implementation. The legacy Kotlin scripts
 * remain only as a build/test fallback and an upgrade safety net if an older
 * APK layout is missing the ARM64 library.
 */
internal object RustSessionControl {
    private val loadResult: Result<Unit> by lazy {
        runCatching { System.loadLibrary("linuxhub_sessiond") }
            .onFailure { System.err.println("RustSessionControl unavailable: ${it.message}") }
    }

    fun systemServicesScript(): String? =
        call { nativeSystemServicesScript() }

    fun sessionScript(display: String, username: String, gpuMode: GpuRenderMode): String? =
        call { nativeSessionScript(display, username, gpuMode.id) }

    fun stopScript(username: String): String? =
        call { nativeStopScript(username) }

    private inline fun <T> call(block: () -> T): T? {
        if (loadResult.isFailure) return null
        return runCatching(block)
            .onFailure { System.err.println("RustSessionControl failed: ${it.message}") }
            .getOrNull()
    }

    private external fun nativeSystemServicesScript(): String

    private external fun nativeSessionScript(
        display: String,
        username: String,
        gpuMode: String,
    ): String

    private external fun nativeStopScript(username: String): String
}
