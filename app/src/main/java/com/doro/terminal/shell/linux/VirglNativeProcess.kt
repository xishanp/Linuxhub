package com.doro.terminal.shell.linux

/**
 * Starts the ARM64 VirGL renderer inside the Android app process. The helper
 * forks before loading the vendor EGL driver, so the server survives the short
 * JNI call while PRoot guests connect through a Unix socket.
 */
internal object VirglNativeProcess {
    val available: Boolean = runCatching {
        System.loadLibrary("doro_virgl_process")
        true
    }.getOrDefault(false)

    external fun nativeStart(executable: String, socket: String, libraryDir: String): Long
    external fun nativeStop()
    external fun nativeIsRunning(generation: Long): Boolean
    external fun nativeConnectProbe(generation: Long, socket: String): Int
}
