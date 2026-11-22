package com.doro.terminal.shell.desktop

import android.content.Context
import android.os.Build
import android.os.Process
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persistent, app-owned diagnostics for the desktop bring-up path.
 *
 * Native and container logs are not reliably available through logcat on
 * Android 13+ and are impossible to inspect from a remote device.  Keep the
 * small set of lifecycle markers in a file the app can display and share.
 */
object DesktopDiagnostics {
    private const val FILE_NAME = "x11-diagnostic.log"
    private const val MAX_BYTES = 512L * 1024L
    private const val KEEP_BYTES = 256L * 1024L
    private val lock = Any()

    fun path(context: Context): String = logFile(context).path

    fun append(context: Context, tag: String, message: String) {
        synchronized(lock) {
            runCatching {
                val file = logFile(context)
                file.parentFile?.mkdirs()
                trimIfNeeded(file)
                val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
                FileOutputStream(file, true).bufferedWriter().use { writer ->
                    writer.append("$timestamp [$tag] $message")
                    if (!message.endsWith('\n')) writer.append('\n')
                }
            }
        }
    }

    fun ensureSessionHeader(context: Context) {
        append(
            context,
            "app",
            "LinuxHub pid=${Process.myPid()} sdk=${Build.VERSION.SDK_INT} " +
                "device=${Build.MANUFACTURER}/${Build.MODEL} abi=${Build.SUPPORTED_ABIS.joinToString()}",
        )
    }

    private fun trimIfNeeded(file: File) {
        if (file.length() <= MAX_BYTES) return
        val retained = file.readBytes().takeLast(KEEP_BYTES.toInt()).toByteArray()
        file.writeBytes(retained)
    }

    fun append(context: Context, tag: String, failure: Throwable) {
        append(context, tag, failure.stackTraceToString())
    }

    fun readTail(context: Context, maxChars: Int = 24_000): String {
        require(maxChars > 0) { "maxChars must be positive" }
        return synchronized(lock) {
            runCatching {
                val file = logFile(context)
                if (!file.isFile) return@synchronized ""
                val text = file.readText()
                if (text.length <= maxChars) text else text.takeLast(maxChars)
            }.getOrDefault("")
        }
    }

    private fun logFile(context: Context): File =
        File(context.filesDir, "linux/logs/$FILE_NAME")
}
