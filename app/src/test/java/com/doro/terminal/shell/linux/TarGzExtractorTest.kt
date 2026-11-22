package com.doro.terminal.shell.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files

class TarGzExtractorTest {
    @Test
    fun symbolicLinksAreCreatedThroughTheConfiguredOperations() {
        val destination = Files.createTempDirectory("linuxhub-tar-symlink").toFile()
        val links = RecordingLinks()
        try {
            TarGzExtractor.extractWithLinks(
                source = symlinkArchive(name = "bin", target = "usr/bin"),
                destination = destination,
                linkOperations = links,
            )

            assertEquals("usr/bin", links.symbolicTarget)
            assertEquals(File(destination, "bin").path, links.symbolicPath?.path)
        } finally {
            destination.deleteRecursively()
        }
    }

    @Test
    fun symbolicLinkFailureAbortsExtractionInsteadOfBeingSilentlyIgnored() {
        val destination = Files.createTempDirectory("linuxhub-tar-symlink-failure").toFile()
        val links = RecordingLinks(failSymbolicLink = true)
        try {
            val error = runCatching {
                TarGzExtractor.extractWithLinks(
                    source = symlinkArchive(name = "bin", target = "usr/bin"),
                    destination = destination,
                    linkOperations = links,
                )
            }.exceptionOrNull()

            assertNotNull(error)
            assertTrue(error is IllegalStateException)
            assertTrue(error?.message.orEmpty().contains("RootFS符号链接创建失败"))
            assertTrue(error?.message.orEmpty().contains("bin -> usr/bin"))
        } finally {
            destination.deleteRecursively()
        }
    }

    @Test
    fun existingPackageSymlinkIsReplacedWithoutFollowingIt() {
        val destination = Files.createTempDirectory("linuxhub-tar-symlink-replace").toFile()
        val links = RecordingLinks()
        val output = File(destination, "usr/lib/libgbm.so.1")
        try {
            output.parentFile?.mkdirs()
            output.writeText("old")
            File(output.parentFile, "libgbm.so.1.0.0").writeText("new")

            TarGzExtractor.extractWithLinks(
                source = symlinkArchive(
                    name = "usr/lib/libgbm.so.1",
                    target = "libgbm.so.1.0.0",
                ),
                destination = destination,
                linkOperations = links,
            )

            assertFalse(links.existingPathAtLinkTime!!)
            assertEquals("libgbm.so.1.0.0", links.symbolicTarget)
        } finally {
            destination.deleteRecursively()
        }
    }

    @Test
    fun existingDirectoryIsNeverDeletedToMakeRoomForAFileEntry() {
        val destination = Files.createTempDirectory("linuxhub-tar-directory-conflict").toFile()
        val links = RecordingLinks()
        try {
            File(destination, "bin").mkdirs()
            val error = runCatching {
                TarGzExtractor.extractWithLinks(
                    source = symlinkArchive(name = "bin", target = "usr/bin"),
                    destination = destination,
                    linkOperations = links,
                )
            }.exceptionOrNull()

            assertNotNull(error)
            assertTrue(error?.message.orEmpty().contains("RootFS路径类型冲突"))
            assertTrue(File(destination, "bin").isDirectory)
        } finally {
            destination.deleteRecursively()
        }
    }

    @Test
    fun filePermissionsKeepSetuidAndReadBitsFromTheArchive() {
        val destination = Files.createTempDirectory("linuxhub-tar-setuid").toFile()
        val links = RecordingLinks()
        val modes = mutableMapOf<String, Int>()
        try {
            TarGzExtractor.extractWithLinks(
                source = regularFileArchive(name = "usr/bin/su", content = "binary", mode = 0x4ED),
                destination = destination,
                linkOperations = links,
                setMode = { path, mode -> modes[path.relativeTo(destination).path] = mode },
            )

            assertEquals(0x4ED, modes["usr/bin/su"])
            assertEquals("binary", File(destination, "usr/bin/su").readText())
        } finally {
            destination.deleteRecursively()
        }
    }

    @Test
    fun extractEntryWritesOnlyTheRequestedPackage() {
        val directory = Files.createTempDirectory("linuxhub-tar-entry").toFile()
        val destination = File(directory, "wanted.deb")
        try {
            TarGzExtractor.extractEntry(
                source = multiFileArchive(
                    "packages/unwanted.deb" to "unwanted",
                    "packages/wanted.deb" to "wanted",
                ),
                destination = destination,
                entryName = "packages/wanted.deb",
            )

            assertEquals("wanted", destination.readText())
            assertFalse(File(directory, "unwanted.deb").exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    private class RecordingLinks(
        private val failSymbolicLink: Boolean = false,
    ) : TarLinkOperations {
        var symbolicTarget: String? = null
        var symbolicPath: File? = null
        var existingPathAtLinkTime: Boolean? = null

        override fun symbolicLink(target: String, linkPath: File) {
            if (failSymbolicLink) throw IOException("symlink denied")
            existingPathAtLinkTime = linkPath.exists()
            symbolicTarget = target
            symbolicPath = linkPath
        }

        override fun hardLink(target: File, linkPath: File) {
            error("unexpected hard link: ${target.path} -> ${linkPath.path}")
        }
    }

    private fun symlinkArchive(name: String, target: String): InputStream {
        return archive(
            name = name,
            mode = 493,
            type = '2',
            content = ByteArray(0),
            linkTarget = target,
        )
    }

    private fun regularFileArchive(name: String, content: String, mode: Int): InputStream =
        archive(name, mode, '0', content.toByteArray(Charsets.UTF_8))

    private fun multiFileArchive(vararg entries: Pair<String, String>): InputStream {
        val output = ByteArrayOutputStream()
        entries.forEach { (name, content) ->
            val bytes = content.toByteArray(Charsets.UTF_8)
            val header = ByteArray(512)
            writeText(header, 0, 100, name)
            writeOctal(header, 100, 8, 493)
            writeOctal(header, 108, 8, 0)
            writeOctal(header, 116, 8, 0)
            writeOctal(header, 124, 12, bytes.size)
            writeOctal(header, 136, 12, 0)
            header[156] = '0'.code.toByte()
            writeText(header, 257, 6, "ustar\u0000")
            writeText(header, 263, 2, "00")
            header.fill(' '.code.toByte(), 148, 156)
            writeText(header, 148, 8, String.format("%06o\u0000 ", header.sumOf { it.toInt() and 0xff }))
            output.write(header)
            output.write(bytes)
            repeat((512 - bytes.size % 512) % 512) { output.write(0) }
        }
        output.write(ByteArray(1024))
        return ByteArrayInputStream(output.toByteArray())
    }

    private fun archive(
        name: String,
        mode: Int,
        type: Char,
        content: ByteArray,
        linkTarget: String = "",
    ): InputStream {
        val header = ByteArray(512)
        writeText(header, 0, 100, name)
        writeOctal(header, 100, 8, mode)
        writeOctal(header, 108, 8, 0)
        writeOctal(header, 116, 8, 0)
        writeOctal(header, 124, 12, content.size)
        writeOctal(header, 136, 12, 0)
        header[156] = type.code.toByte()
        writeText(header, 157, 100, linkTarget)
        writeText(header, 257, 6, "ustar\u0000")
        writeText(header, 263, 2, "00")
        header.fill(' '.code.toByte(), 148, 156)
        writeText(header, 148, 8, String.format("%06o\u0000 ", header.sumOf { it.toInt() and 0xff }))

        return ByteArrayInputStream(
            ByteArrayOutputStream().apply {
                write(header)
                write(content)
                repeat((512 - content.size % 512) % 512) { write(0) }
                write(ByteArray(1024))
            }.toByteArray(),
        )
    }

    private fun writeText(target: ByteArray, offset: Int, length: Int, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= length)
        bytes.copyInto(target, offset)
    }

    private fun writeOctal(target: ByteArray, offset: Int, length: Int, value: Int) {
        writeText(target, offset, length, String.format("%0${length - 1}o\u0000", value))
    }
}
