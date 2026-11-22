package com.doro.terminal.shell.linux

import java.io.File
import java.security.MessageDigest

internal data class MesaRuntimeFile(
    val relativePath: String,
    val size: Long,
    val sha256: String,
)

internal object MesaGpuRuntime {
    const val VERSION = "mesa-27.0.0-devel-20261025-r2"

    // swrast loads libdril_dri.so, which then resolves the matching Gallium
    // driver. A valid Mesa 27 marker alone is not enough: older rootfs images
    // can already contain that marker while shipping the stale DRI dispatcher.
    val criticalFiles = listOf(
        MesaRuntimeFile(
            relativePath = "usr/lib/aarch64-linux-gnu/dri/libdril_dri.so",
            size = 144_416L,
            sha256 = "f13f1c4a93ce162c7bea9c86ced3c66ce38fbae0dc4e3eb7a425134f94e5c183",
        ),
        MesaRuntimeFile(
            relativePath = "usr/lib/aarch64-linux-gnu/libgallium-27.0.0-devel.so",
            size = 28_584_616L,
            sha256 = "3bcc5cc77d75dc477956b653f93959227a063051a9bedbef3093e43fadebddc3",
        ),
    )

    fun isReady(
        rootfsDir: File,
        marker: File,
        files: List<MesaRuntimeFile> = criticalFiles,
    ): Boolean =
        marker.isFile &&
            marker.readText().trim() == VERSION &&
            filesReady(rootfsDir, files)

    fun filesReady(
        rootfsDir: File,
        files: List<MesaRuntimeFile> = criticalFiles,
    ): Boolean = files.all { expected ->
        val actual = File(rootfsDir, expected.relativePath)
        actual.isFile &&
            actual.length() == expected.size &&
            sha256(actual) == expected.sha256
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }
}
