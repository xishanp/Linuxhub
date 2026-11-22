package com.doro.terminal.shell.linux

import android.os.Process
import java.io.File

object GetifaddrsBridge {
    const val CONTAINER_LIBRARY = "/usr/local/lib/doro/libgetifaddrs_bridge.so"
    const val CONTAINER_SOCKET = "/tmp/.getifaddrs-bridge"

    fun preloadExpression(existing: String): String =
        listOf(CONTAINER_LIBRARY, existing).filter { it.isNotBlank() }.joinToString(":")

    fun installClient(rootfsDir: File, source: File) {
        check(source.isFile) { "getifaddrs客户端库缺失" }
        val target = File(rootfsDir, CONTAINER_LIBRARY.removePrefix("/"))
        target.parentFile?.mkdirs()
        val temporary = File(
            target.parentFile,
            "${target.name}.tmp-${Process.myPid()}-${Thread.currentThread().id}-${System.nanoTime()}",
        )
        source.inputStream().use { input -> temporary.outputStream().use(input::copyTo) }
        temporary.setReadable(true, false)
        temporary.setExecutable(true, false)
        try {
            check(temporary.renameTo(target)) { "getifaddrs客户端库替换失败" }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }
}