package com.doro.terminal.shell.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths

class MergedUsrLayoutTest {
    @Test
    fun realLibDirectoryIsMergedAndReplacedByTheCanonicalSymlink() {
        val root = Files.createTempDirectory("linuxhub-merged-usr").toFile()
        try {
            root.resolve("lib/systemd").mkdirs()
            root.resolve("lib/systemd/vendor-unit.service").writeText("vendor")
            root.resolve("lib/udev/rules.d").mkdirs()
            root.resolve("lib/udev/rules.d/99-vendor.rules").writeText("rule")
            root.resolve("usr/lib/systemd").mkdirs()
            root.resolve("usr/lib/systemd/ubuntu-unit.service").writeText("ubuntu")
            root.resolve("usr/lib/aarch64-linux-gnu").mkdirs()
            root.resolve("usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1").writeText("loader")
            Files.createSymbolicLink(
                root.resolve("usr/lib/ld-linux-aarch64.so.1").toPath(),
                Paths.get("aarch64-linux-gnu/ld-linux-aarch64.so.1"),
            )

            val moved = MergedUsrLayout.repair(root)

            assertTrue(moved >= 2)
            assertTrue(Files.isSymbolicLink(root.resolve("lib").toPath()))
            assertEquals("usr/lib", Files.readSymbolicLink(root.resolve("lib").toPath()).toString())
            assertEquals("loader", root.resolve("lib/ld-linux-aarch64.so.1").readText())
            assertEquals("vendor", root.resolve("usr/lib/systemd/vendor-unit.service").readText())
            assertEquals("ubuntu", root.resolve("usr/lib/systemd/ubuntu-unit.service").readText())
            assertEquals("rule", root.resolve("usr/lib/udev/rules.d/99-vendor.rules").readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun existingCanonicalLinkIsLeftUntouched() {
        val root = Files.createTempDirectory("linuxhub-merged-usr-good").toFile()
        try {
            root.resolve("usr/lib").mkdirs()
            Files.createSymbolicLink(root.resolve("lib").toPath(), Paths.get("usr/lib"))

            assertEquals(0, MergedUsrLayout.repair(root))
            assertEquals("usr/lib", Files.readSymbolicLink(root.resolve("lib").toPath()).toString())
        } finally {
            root.deleteRecursively()
        }
    }
}
