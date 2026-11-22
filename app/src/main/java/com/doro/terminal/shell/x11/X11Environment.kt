package com.doro.terminal.shell.x11

import android.content.Context
import android.os.Build
import android.os.Process
import com.doro.terminal.shell.desktop.DesktopDiagnostics
import com.doro.terminal.shell.linux.TarGzExtractor
import java.io.File

/** Android-side prerequisites that must exist before Lorie starts. */
internal object X11Environment {
    private const val XKB_ASSET = "x11/xkb-data.tar"
    private val REQUIRED_XKB_FILES = listOf(
        "rules/evdev",
        "keycodes/evdev",
        "symbols/us",
        "compat/complete",
    )

    data class Prepared(
        val tmpDir: File,
        val xkbDir: File,
        val nativeLibraryDir: File,
    )

    fun prepare(context: Context): Prepared {
        val tmpDir = File(context.filesDir, "linux/tmp").apply { mkdirs() }
        val socketDir = File(tmpDir, ".X11-unix").apply { mkdirs() }
        check(socketDir.isDirectory && socketDir.canRead() && socketDir.canWrite()) {
            "X11 socket目录不可用：${socketDir.path}"
        }

        val xkbDir = File(
            context.filesDir,
            "linux/distributions/ubuntu-24.04/rootfs/usr/share/X11/xkb",
        )
        ensureXkb(context, xkbDir)

        val nativeLibraryDir = File(context.applicationInfo.nativeLibraryDir)
        DesktopDiagnostics.append(
            context,
            "x11-env",
            "pid=${Process.myPid()} sdk=${Build.VERSION.SDK_INT} abi=${Build.SUPPORTED_ABIS.joinToString()} " +
                "native=${nativeLibraryDir.path} tmp=${tmpDir.path} xkb=${xkbDir.path}",
        )
        return Prepared(tmpDir, xkbDir, nativeLibraryDir)
    }

    private fun ensureXkb(context: Context, xkbDir: File) {
        val missing = REQUIRED_XKB_FILES.filterNot { File(xkbDir, it).isFile }
        if (missing.isEmpty()) {
            DesktopDiagnostics.append(context, "x11-env", "XKB完整: ${REQUIRED_XKB_FILES.joinToString()}")
            return
        }

        DesktopDiagnostics.append(
            context,
            "x11-env",
            "XKB缺少 ${missing.joinToString()}，从APK资源修复",
        )
        val x11Share = xkbDir.parentFile ?: error("无法定位X11共享目录")
        x11Share.mkdirs()
        context.assets.open(XKB_ASSET).use { input ->
            TarGzExtractor.extract(input, x11Share)
        }
        val stillMissing = REQUIRED_XKB_FILES.filterNot { File(xkbDir, it).isFile }
        check(stillMissing.isEmpty()) {
            "X11键盘配置修复失败，缺少：${stillMissing.joinToString()}"
        }
        DesktopDiagnostics.append(context, "x11-env", "XKB修复完成")
    }
}
