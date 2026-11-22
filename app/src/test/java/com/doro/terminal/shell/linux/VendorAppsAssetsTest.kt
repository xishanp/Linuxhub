package com.doro.terminal.shell.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.util.zip.GZIPInputStream

class VendorAppsAssetsTest {
    private val offline = File("src/main/assets/offline")

    @Test
    fun manifestContainsEveryDesktopApplicationAndVendorPackage() {
        val packages = File(offline, "vendor-packages.txt")
            .readLines()
            .filter(String::isNotBlank)

        assertTrue(packages.size > 200)
        assertTrue(packages.none { it.startsWith("#") })
        listOf(
            "libreoffice_24.2.7-0ubuntu0.24.04.7_arm64.deb",
            "evince_46.3.1-0ubuntu1.1_arm64.deb",
            "rhythmbox_3.4.7-2ubuntu6_arm64.deb",
            "totem_43.0-2ubuntu4_arm64.deb",
            "shotwell_0.32.6-1ubuntu3_arm64.deb",
            "gnome-disk-utility_46.0-1ubuntu3_arm64.deb",
            "gnome-system-monitor_46.0-1build1_arm64.deb",
            "file-roller_44.3-0ubuntu2_arm64.deb",
            "google-chrome-stable_158.0.8127.36-1_arm64.deb",
            "thunderbird_140.7.1+build1-0ubuntu0.22.04.1_arm64.deb",
        ).forEach { expected ->
            assertTrue("manifest missing: $expected", expected in packages)
        }
    }

    @Test
    fun archiveContainsExactlyTheManifestedDebsAndOfflineInstaller() {
        val manifest = File(offline, "vendor-packages.txt")
            .readLines()
            .filter(String::isNotBlank)
            .map { File(it).name }
        val archive = File(offline, "vendor-apps-arm64.tar")
        assertTrue("缺少 vendor 离线包", archive.isFile)

        val entries = tarEntries(archive)
        assertTrue("./install-vendor.sh" in entries)
        assertTrue("./vendor-app-tasks.tsv" in entries)
        assertTrue("./vendor-dependencies.txt" in entries)
        val archivedPackages = entries
            .filter { it.startsWith("./packages/") && it.endsWith(".deb") }
            .map { it.substringAfterLast('/') }
            .toSet()
        assertEquals(manifest.toSet(), archivedPackages)
        assertEquals(manifest.size, archivedPackages.size)
    }

    @Test
    fun taskManifestsExposeEveryApplicationAndSharedDependencies() {
        val tasks = VendorAppTasks.parse(File(offline, "vendor-app-tasks.tsv").readText())
        val dependencies = VendorAppTasks.parseDependencies(
            File(offline, "vendor-dependencies.txt").readText(),
        )

        assertEquals(35, tasks.size)
        assertTrue(dependencies.size > 200)
        assertTrue(tasks.any { it.label == "LibreOffice" })
        assertTrue(tasks.any { it.label == "Google Chrome" })
        assertTrue(tasks.any { it.label == "Thunderbird" })
    }

    @Test
    fun installerUsesTheOneDotOneNineOneOneOverlayRoute() {
        val script = File(offline, "vendor-apps-arm64.tar").let(::tarFile)
        assertTrue(script.contains("dpkg-deb --fsys-tarfile \"\$deb\""))
        assertTrue(script.contains("--keep-directory-symlink"))
        assertTrue(script.contains("/etc/ca-certificates.conf"))
        assertTrue(script.contains("update-ca-certificates --fresh"))
        assertFalse(script.contains("apt-get"))
        assertFalse(script.contains("dpkg -i"))
        assertFalse(script.contains("dpkg --configure"))
    }

    private fun tarFile(archive: File): String {
        GZIPInputStream(archive.inputStream().buffered()).use { input ->
            val header = ByteArray(BLOCK_SIZE)
            while (true) {
                val count = readAtMost(input, header)
                if (count == 0) break
                assertEquals(BLOCK_SIZE, count)
                if (header.all { it == 0.toByte() }) break
                val name = String(header, 0, 100, Charsets.UTF_8)
                    .trimEnd('\u0000')
                    .removePrefix("./")
                val size = String(header, 124, 12, Charsets.US_ASCII)
                    .trim { it == '\u0000' || it == ' ' }
                    .ifEmpty { "0" }
                    .toLong(8)
                if (name == "install-vendor.sh") {
                    val content = ByteArray(size.toInt())
                    assertEquals(content.size, readAtMost(input, content))
                    return content.toString(Charsets.UTF_8)
                }
                skipExactly(input, ((size + BLOCK_SIZE - 1) / BLOCK_SIZE) * BLOCK_SIZE)
            }
        }
        error("install-vendor.sh missing")
    }

    private fun tarEntries(archive: File): Set<String> {
        val entries = linkedSetOf<String>()
        GZIPInputStream(archive.inputStream().buffered()).use { input ->
            val header = ByteArray(BLOCK_SIZE)
            while (true) {
                val count = readAtMost(input, header)
                if (count == 0) break
                assertEquals(BLOCK_SIZE, count)
                if (header.all { it == 0.toByte() }) break
                val name = String(header, 0, 100, Charsets.UTF_8)
                    .trimEnd('\u0000')
                    .removePrefix("./")
                entries += "./$name"
                val size = String(header, 124, 12, Charsets.US_ASCII)
                    .trim { it == '\u0000' || it == ' ' }
                    .ifEmpty { "0" }
                    .toLong(8)
                skipExactly(input, ((size + BLOCK_SIZE - 1) / BLOCK_SIZE) * BLOCK_SIZE)
            }
        }
        return entries
    }

    private fun readAtMost(input: InputStream, buffer: ByteArray): Int {
        var offset = 0
        while (offset < buffer.size) {
            val count = input.read(buffer, offset, buffer.size - offset)
            if (count < 0) break
            offset += count
        }
        return offset
    }

    private fun skipExactly(input: InputStream, bytes: Long) {
        var remaining = bytes
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else {
                assertTrue(input.read() >= 0)
                remaining--
            }
        }
    }

    private companion object {
        const val BLOCK_SIZE = 512
    }
}
