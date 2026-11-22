package com.doro.terminal.shell.linux

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class MesaGpuRuntimeTest {
    @Test
    fun staleCriticalFileInvalidatesAnExistingMarker() {
        val rootfs = Files.createTempDirectory("linuxhub-mesa-stale").toFile()
        try {
            val runtime = File(rootfs, "usr/lib/runtime.so")
            runtime.parentFile?.mkdirs()
            runtime.writeText("stale")
            val expectedFile = File(rootfs, "expected.so").apply { writeText("fresh") }
            val marker = File(rootfs, ".mesa-ready").apply { writeText(MesaGpuRuntime.VERSION + "\n") }
            val expected = listOf(
                MesaRuntimeFile(
                    relativePath = "usr/lib/runtime.so",
                    size = expectedFile.length(),
                    sha256 = MesaGpuRuntime.sha256(expectedFile),
                )
            )

            assertFalse(MesaGpuRuntime.isReady(rootfs, marker, expected))
        } finally {
            rootfs.deleteRecursively()
        }
    }

    @Test
    fun filesCanBeVerifiedBeforeTheMarkerIsWritten() {
        val rootfs = Files.createTempDirectory("linuxhub-mesa-files").toFile()
        try {
            val runtime = File(rootfs, "usr/lib/runtime.so")
            runtime.parentFile?.mkdirs()
            runtime.writeText("patched")
            val expected = listOf(
                MesaRuntimeFile(
                    relativePath = "usr/lib/runtime.so",
                    size = runtime.length(),
                    sha256 = MesaGpuRuntime.sha256(runtime),
                )
            )

            assertTrue(MesaGpuRuntime.filesReady(rootfs, expected))
        } finally {
            rootfs.deleteRecursively()
        }
    }

    @Test
    fun correctMarkerAndFilesAreReady() {
        val rootfs = Files.createTempDirectory("linuxhub-mesa-ready").toFile()
        try {
            val runtime = File(rootfs, "usr/lib/runtime.so")
            runtime.parentFile?.mkdirs()
            runtime.writeText("patched")
            val marker = File(rootfs, ".mesa-ready").apply { writeText(MesaGpuRuntime.VERSION + "\n") }
            val expected = listOf(
                MesaRuntimeFile(
                    relativePath = "usr/lib/runtime.so",
                    size = runtime.length(),
                    sha256 = MesaGpuRuntime.sha256(runtime),
                )
            )

            assertTrue(MesaGpuRuntime.isReady(rootfs, marker, expected))
        } finally {
            rootfs.deleteRecursively()
        }
    }
}
