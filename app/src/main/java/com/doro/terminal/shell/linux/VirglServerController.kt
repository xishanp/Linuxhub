package com.doro.terminal.shell.linux

import android.content.Context
import android.util.Log
import java.io.Closeable
import java.io.File

/**
 * Host-side VirGL endpoint for MediaTek: GUEST OpenGL -> Mesa virpipe ->
 * VTEST socket -> this Android process -> vendor EGL/Mali.
 *
 * The guest Mesa stack stays inside the normal Ubuntu rootfs. This controller
 * only owns the Android-host renderer and never deletes software drivers, so a
 * failed Mali path can always fall back to llvmpipe instead of black-screening.
 */
internal class VirglServerController(
    private val context: Context,
    private val paths: ContainerPaths,
) : Closeable {
    @Volatile private var generation = 0L
    private val installDir = File(paths.runtimeDir, "gpu/virgl")
    private val serverBinary = File(installDir, "virgl_test_server_android")
    val socket = File(paths.rootfsDir, "tmp/.virgl_test")

    @Synchronized
    fun start(timeoutMs: Long = START_TIMEOUT_MS) {
        check(VirglNativeProcess.available) { "VirGL Native 进程管理器不可用" }
        install()
        if (isRunning()) return
        stop()
        socket.parentFile?.mkdirs()
        socket.delete()
        generation = VirglNativeProcess.nativeStart(serverBinary.path, socket.path, installDir.path)
        check(generation > 0L) { "VirGL Native 进程创建失败" }
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!VirglNativeProcess.nativeIsRunning(generation)) {
                stop()
                error("VirGL 服务端异常退出")
            }
            if (socket.exists() && VirglNativeProcess.nativeConnectProbe(generation, socket.path) == 1) {
                Log.i(TAG, "VirGL server ready socket=${socket.path}")
                return
            }
            Thread.sleep(POLL_MS)
        }
        stop()
        error("VirGL 服务端启动超时或连接探测失败")
    }

    fun isRunning(): Boolean =
        generation > 0L && VirglNativeProcess.available &&
            VirglNativeProcess.nativeIsRunning(generation) && socket.exists() &&
            VirglNativeProcess.nativeConnectProbe(generation, socket.path) == 1

    @Synchronized
    override fun close() = stop()

    @Synchronized
    fun stop() {
        if (VirglNativeProcess.available) runCatching { VirglNativeProcess.nativeStop() }
        generation = 0L
        socket.delete()
    }

    private fun install() {
        installDir.mkdirs()
        copyAsset("gpu/virgl/arm64-v8a/virgl_test_server_android", serverBinary, executable = true)
        copyAsset("gpu/virgl/arm64-v8a/libepoxy.so", File(installDir, "libepoxy.so"))
        copyAsset("gpu/virgl/arm64-v8a/libvirglrenderer.so", File(installDir, "libvirglrenderer.so"))
        serverBinary.setExecutable(true, true)
        check(serverBinary.canExecute()) { "VirGL 服务端不可执行" }
    }

    private fun copyAsset(asset: String, destination: File, executable: Boolean = false) {
        destination.parentFile?.mkdirs()
        context.assets.open(asset).use { input ->
            destination.outputStream().use(input::copyTo)
        }
        if (executable) destination.setExecutable(true, true)
    }

    private companion object {
        const val TAG = "LinuxHubVirGL"
        const val START_TIMEOUT_MS = 8_000L
        const val POLL_MS = 50L
    }
}
