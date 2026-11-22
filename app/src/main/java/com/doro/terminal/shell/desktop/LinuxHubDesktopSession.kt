package com.doro.terminal.shell.desktop

import android.content.Context
import android.graphics.Point
import android.os.Build
import android.preference.PreferenceManager
import android.util.Log
import android.view.WindowManager
import com.doro.terminal.shell.linux.LinuxContainerManager
import com.doro.terminal.shell.x11.LorieX11Runtime
import com.doro.terminal.shell.x11.X11RuntimeManager
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Owns only the local GNOME display session: start, observe, and stop. */
object LinuxHubDesktopSession {
    private const val TAG = "LinuxHubDesktop"

    data class Status(
        val running: Boolean,
        val pid: Long?,
        val display: String,
        val x11State: String,
        val captureReady: Boolean,
        val firstFrameReady: Boolean,
        val width: Int,
        val height: Int,
        val uptimeSeconds: Long,
        val lastError: String?,
        val stoppedByUser: Boolean,
        val outputTail: String,
    )

    private val lock = Any()
    private val logLock = Any()
    private val output = StringBuilder()
    @Volatile private var process: Process? = null
    @Volatile private var startedAt: Long = 0
    @Volatile private var lastError: String? = null
    @Volatile private var stoppedByUser = false
    @Volatile private var width: Int = 1280
    @Volatile private var height: Int = 1024
    @Volatile private var outputReader: Thread? = null
    private val starting = AtomicBoolean(false)

    private fun runtime(context: Context): LorieX11Runtime =
        X11RuntimeManager.get(context).runtime as LorieX11Runtime

    fun start(
        context: Context,
        requestedWidth: Int? = null,
        requestedHeight: Int? = null,
    ): Status {
        val nativeSize = nativeDesktopSize(context)
        val targetWidth = requestedWidth ?: nativeSize.first
        val targetHeight = requestedHeight ?: nativeSize.second
        require(targetWidth in 320..3840 && targetHeight in 240..2160) { "桌面尺寸超出范围" }
        synchronized(lock) {
            val manager = LinuxContainerManager(context)
            require(manager.isInitialized()) { "Ubuntu 尚未初始化" }
            val gpuAcceleration = PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean("gpuAcceleration", true)
            manager.ensureDesktopRuntimeAssets(gpuAcceleration)
            DesktopSessionService.start(context)
            process?.takeIf { it.isAlive }?.let {
                val transport = runtime(context)
                if (transport.state.name == "RUNNING_ATTACHED") {
                    Log.i(TAG, "Desktop already running; keeping attached Surface size")
                } else {
                    width = targetWidth
                    height = targetHeight
                    transport.sendHeadlessWindowSize(width, height)
                }
                if (!transport.isDesktopFrameReady()) transport.awaitFirstDesktopFrame()
                return status(context)
            }
            check(!starting.get()) { "桌面正在启动" }
            starting.set(true)
            try {
                width = targetWidth
                height = targetHeight
                val runtime = runtime(context)
                runtime.start()
                runtime.connectHeadless(width, height)
                // Boot GNOME against the headless renderer and prove a real
                // desktop frame before Android creates a SurfaceView. Creating
                // the visible surface first leaves OplusHecate waiting on a
                // buffer for the entire GNOME start and produces 3s warnings.
                val launched = manager.startDesktop(runtime.display, gpuAcceleration)
                process = launched
                startedAt = System.currentTimeMillis()
                lastError = null
                stoppedByUser = false
                synchronized(logLock) { output.clear() }
                attachOutputReader(context, launched)
                runtime.awaitFirstDesktopFrame()
                return status(context)
            } catch (failure: Throwable) {
                lastError = failure.message ?: failure.javaClass.simpleName
                Log.e(TAG, "Desktop start failed", failure)
                process?.let { managed ->
                    managed.destroy()
                    if (managed.isAlive) managed.destroyForcibly()
                }
                process = null
                startedAt = 0
                runCatching { manager.stopDesktopSession() }
                runCatching { manager.stopDesktopServices() }
                runCatching { DesktopSessionService.stop(context) }
                throw failure
            } finally {
                starting.set(false)
            }
        }
    }

    fun status(context: Context): Status {
        val managed = process
        val alive = managed?.isAlive == true
        if (managed != null && !alive && startedAt > 0 && lastError == null) {
            val code = runCatching { managed.exitValue() }.getOrDefault(-1)
            lastError = "GNOME 已退出，退出码 $code"
        }
        val now = System.currentTimeMillis()
        val transport = runtime(context)
        val x11State = transport.state.name
        val attachedSize = transport.attachedSize()
        return Status(
            running = alive,
            pid = null,
            display = transport.display,
            x11State = x11State,
            captureReady = x11State in setOf("RUNNING_HEADLESS", "RUNNING_ATTACHED"),
            firstFrameReady = transport.isDesktopFrameReady(),
            width = attachedSize?.first ?: width,
            height = attachedSize?.second ?: height,
            uptimeSeconds = if (alive && startedAt > 0) (now - startedAt) / 1000 else 0,
            lastError = lastError,
            stoppedByUser = stoppedByUser && !alive,
            outputTail = synchronized(logLock) { output.toString() },
        )
    }

    fun stop(context: Context): Status {
        synchronized(lock) {
            try {
                process?.let { managed ->
                    managed.destroy()
                    for (attempt in 0 until 20) {
                        if (!managed.isAlive) break
                        Thread.sleep(50)
                    }
                    if (managed.isAlive) managed.destroyForcibly()
                }
                process = null
                startedAt = 0
                stoppedByUser = true
                LinuxContainerManager(context).stopDesktopSession()
                LinuxContainerManager(context).stopDesktopServices()
                runtime(context).stop()
            } finally {
                DesktopSessionService.stop(context)
            }
        }
        return status(context)
    }

    private fun attachOutputReader(context: Context, managed: Process) {
        val logFile = File(context.filesDir, "linux/logs/desktop-session.log")
        logFile.parentFile?.mkdirs()
        if (logFile.length() > 512_000L) logFile.delete()
        outputReader = Thread {
            runCatching {
                managed.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        synchronized(logLock) {
                            output.appendLine(line)
                            if (output.length > 64_000) output.delete(0, output.length - 64_000)
                            runCatching { logFile.appendText("${System.currentTimeMillis()} $line\n") }
                        }
                    }
                }
            }
        }.apply {
            name = "linuxhub-desktop-output"
            isDaemon = true
            start()
        }
    }

    private fun nativeDesktopSize(context: Context): Pair<Int, Int> {
        val windowManager = context.getSystemService(WindowManager::class.java)
        val physicalWidth: Int
        val physicalHeight: Int
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            physicalWidth = bounds.width()
            physicalHeight = bounds.height()
        } else {
            val size = Point()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealSize(size)
            physicalWidth = size.x
            physicalHeight = size.y
        }
        check(physicalWidth > 0 && physicalHeight > 0) { "无法读取设备桌面尺寸" }
        return maxOf(physicalWidth, physicalHeight) to minOf(physicalWidth, physicalHeight)
    }
}
