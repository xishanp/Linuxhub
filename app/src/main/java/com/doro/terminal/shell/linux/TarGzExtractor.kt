package com.doro.terminal.shell.linux

import android.system.Os
import java.io.BufferedInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.io.PushbackInputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.PosixFilePermission
import java.util.zip.GZIPInputStream

internal interface TarLinkOperations {
    fun symbolicLink(target: String, linkPath: File)
    fun hardLink(target: File, linkPath: File)
}

private object AndroidTarLinkOperations : TarLinkOperations {
    override fun symbolicLink(target: String, linkPath: File) {
        Os.symlink(target, linkPath.path)
    }

    override fun hardLink(target: File, linkPath: File) {
        Os.link(target.path, linkPath.path)
    }
}

object TarGzExtractor {
    private const val BLOCK = 512

    fun extract(
        source: InputStream,
        destination: File,
        totalBytes: Long = -1L,
        onProgress: (Int) -> Unit = {}
    ) = extractWithLinks(
        source = source,
        destination = destination,
        linkOperations = AndroidTarLinkOperations,
        setMode = ::setAndroidFileMode,
        totalBytes = totalBytes,
        onProgress = onProgress,
    )

    /**
     * Extracts one regular file from a tar or tar.gz stream without unpacking the
     * rest of the archive. Used for small, versioned container migrations.
     */
    fun extractEntry(
        source: InputStream,
        destination: File,
        entryName: String,
        totalBytes: Long = -1L,
        onProgress: (Int) -> Unit = {},
    ) {
        val expected = normalizeArchiveName(entryName)
        var lastPercent = -1
        val counted = CountingInputStream(source) { bytesRead ->
            if (totalBytes > 0L) {
                val percent = ((bytesRead * 100L) / totalBytes).coerceIn(0L, 100L).toInt()
                if (percent != lastPercent) {
                    lastPercent = percent
                    onProgress(percent)
                }
            }
        }
        val buffered = PushbackInputStream(BufferedInputStream(counted), 2)
        val signature = ByteArray(2)
        val count = buffered.read(signature)
        if (count > 0) buffered.unread(signature, 0, count)
        val archive: InputStream = if (
            count == 2 && signature[0] == 0x1f.toByte() && signature[1] == 0x8b.toByte()
        ) GZIPInputStream(buffered) else buffered
        archive.use { tar ->
            val header = ByteArray(BLOCK)
            while (readBlock(tar, header)) {
                if (header.all { it == 0.toByte() }) break
                val name = text(header, 0, 100)
                val prefix = text(header, 345, 155)
                val relative = normalizeArchiveName(if (prefix.isEmpty()) name else "$prefix/$name")
                val type = header[156].toInt().toChar()
                val size = octal(header, 124, 12)
                val padding = (BLOCK - size % BLOCK) % BLOCK
                if (type == 'x' || type == 'g' || type == 'L' || type == 'K') {
                    skipFully(tar, size + padding)
                    continue
                }
                if ((type == '0' || type == '\u0000') && relative == expected) {
                    destination.parentFile?.mkdirs()
                    destination.outputStream().buffered().use { output ->
                        copyExactly(tar, output, size)
                    }
                    skipFully(tar, padding)
                    return
                }
                skipFully(tar, size + padding)
            }
        }
        error("归档缺少文件：$entryName")
    }

    internal fun extractWithLinks(
        source: InputStream,
        destination: File,
        linkOperations: TarLinkOperations,
        setMode: (File, Int) -> Unit = { _, _ -> },
        totalBytes: Long = -1L,
        onProgress: (Int) -> Unit = {}
    ) {
        destination.mkdirs()
        val directoryModes = mutableListOf<Pair<File, Int>>()
        var lastPercent = -1
        val counted = CountingInputStream(source) { bytesRead ->
            if (totalBytes > 0L) {
                val percent = ((bytesRead * 100L) / totalBytes).coerceIn(0L, 100L).toInt()
                if (percent != lastPercent) {
                    lastPercent = percent
                    onProgress(percent)
                }
            }
        }
        val buffered = PushbackInputStream(BufferedInputStream(counted), 2)
        val signature = ByteArray(2)
        val count = buffered.read(signature)
        if (count > 0) buffered.unread(signature, 0, count)
        val archive: InputStream = if (
            count == 2 && signature[0] == 0x1f.toByte() && signature[1] == 0x8b.toByte()
        ) GZIPInputStream(buffered) else buffered
        archive.use { tar ->
            val header = ByteArray(BLOCK)
            while (readBlock(tar, header)) {
                if (header.all { it == 0.toByte() }) break
                val name = text(header, 0, 100)
                val prefix = text(header, 345, 155)
                val relative = if (prefix.isEmpty()) name else "$prefix/$name"
                val type = header[156].toInt().toChar()
                val size = octal(header, 124, 12)
                val mode = octal(header, 100, 8).toInt()
                if (type == 'x' || type == 'g' || type == 'L' || type == 'K') {
                    skipFully(tar, size)
                    skipFully(tar, (BLOCK - size % BLOCK) % BLOCK)
                    continue
                }
                check(!relative.split('/').any { it.startsWith("PaxHeaders") || it == "PaxHeaders" }) {
                    "归档包含非法PAX路径：$relative"
                }
                val output = safeTarget(destination, relative)
                when (type) {
                    '5' -> {
                        removeNonDirectoryEntry(output)
                        check(output.isDirectory || output.mkdirs()) {
                            "RootFS目录创建失败：${output.path}"
                        }
                        directoryModes += output to mode
                    }
                    '2' -> {
                        output.parentFile?.mkdirs()
                        removeExistingEntry(output)
                        val linkTarget = text(header, 157, 100)
                        try {
                            linkOperations.symbolicLink(linkTarget, output)
                        } catch (error: Exception) {
                            throw IllegalStateException(
                                "RootFS符号链接创建失败：${output.path} -> $linkTarget",
                                error,
                            )
                        }
                        skipFully(tar, size)
                    }
                    '1' -> {
                        output.parentFile?.mkdirs()
                        removeExistingEntry(output)
                        val target = safeTarget(destination, text(header, 157, 100))
                        runCatching { linkOperations.hardLink(target, output) }
                            .getOrElse {
                                check(target.isFile) { "RootFS硬链接目标不存在：${target.path}" }
                                target.inputStream().use { input -> output.outputStream().use(input::copyTo) }
                            }
                        setMode(output, mode)
                        skipFully(tar, size)
                    }
                    else -> {
                        output.parentFile?.mkdirs()
                        removeExistingEntry(output)
                        output.outputStream().buffered().use { copyExactly(tar, it, size) }
                        setMode(output, mode)
                    }
                }
                skipFully(tar, (BLOCK - size % BLOCK) % BLOCK)
            }
            // Apply directory modes only after all children exist. Some rootfs
            // directories are read-only, so applying their mode during the walk
            // would make later entries impossible to create.
            directoryModes.sortedByDescending { it.first.path.length }.forEach { (path, mode) ->
                setMode(path, mode)
            }
        }
    }

    private class CountingInputStream(
        source: InputStream,
        private val onBytesRead: (Long) -> Unit
    ) : FilterInputStream(source) {
        private var bytesRead = 0L

        override fun read(): Int = super.read().also { value ->
            if (value >= 0) report(1)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            super.read(buffer, offset, length).also { count ->
                if (count > 0) report(count.toLong())
            }

        override fun skip(count: Long): Long = super.skip(count).also { skipped ->
            if (skipped > 0) report(skipped)
        }

        private fun report(count: Long) {
            bytesRead += count
            onBytesRead(bytesRead)
        }
    }

    private fun setAndroidFileMode(path: File, mode: Int) {
        val permissions = buildSet {
            if (mode and 0b100000000 != 0) add(PosixFilePermission.OWNER_READ)
            if (mode and 0b010000000 != 0) add(PosixFilePermission.OWNER_WRITE)
            if (mode and 0b001000000 != 0) add(PosixFilePermission.OWNER_EXECUTE)
            if (mode and 0b000100000 != 0) add(PosixFilePermission.GROUP_READ)
            if (mode and 0b000010000 != 0) add(PosixFilePermission.GROUP_WRITE)
            if (mode and 0b000001000 != 0) add(PosixFilePermission.GROUP_EXECUTE)
            if (mode and 0b000000100 != 0) add(PosixFilePermission.OTHERS_READ)
            if (mode and 0b000000010 != 0) add(PosixFilePermission.OTHERS_WRITE)
            if (mode and 0b000000001 != 0) add(PosixFilePermission.OTHERS_EXECUTE)
        }
        Files.setPosixFilePermissions(path.toPath(), permissions)
        // Java's POSIX permission API has no SUID/SGID/sticky bits. Apply the
        // full archive mode through Android once any special bit is present.
        if (mode and 0b111000000000 != 0) Os.chmod(path.path, mode)
    }

    private fun normalizeArchiveName(value: String): String =
        value.replace('\\', '/').removePrefix("./").trimStart('/').trimEnd('/')

    private fun safeTarget(root: File, relative: String): File {
        val normalized = relative
            .removePrefix("./")
            .replace('\\', '/')
        if (normalized.isBlank() || normalized == ".") return root
        check(!normalized.startsWith('/')) {
            "RootFS 包含非法路径：$relative"
        }
        val segments = normalized.split('/').filter { it.isNotEmpty() && it != "." }
        check(segments.none { it == ".." }) { "RootFS 包含非法路径：$relative" }
        // 这里只做词法路径校验，不能 canonicalize。
        // RootFS 内含 /bin -> /usr/bin 等符号链接，解析链接会错误地逃出解压根目录。
        return segments.fold(root) { parent, segment -> File(parent, segment) }
    }

    /** Package upgrades frequently replace an existing symlink with a new one. */
    private fun removeExistingEntry(path: File) {
        val nioPath = path.toPath()
        if (!Files.exists(nioPath, LinkOption.NOFOLLOW_LINKS)) return
        check(!Files.isDirectory(nioPath, LinkOption.NOFOLLOW_LINKS)) {
            "RootFS路径类型冲突：${path.path}"
        }
        check(path.delete()) { "RootFS旧文件删除失败：${path.path}" }
    }

    private fun removeNonDirectoryEntry(path: File) {
        val nioPath = path.toPath()
        if (!Files.exists(nioPath, LinkOption.NOFOLLOW_LINKS)) return
        if (!Files.isDirectory(nioPath, LinkOption.NOFOLLOW_LINKS)) removeExistingEntry(path)
    }

    private fun readBlock(input: InputStream, data: ByteArray): Boolean {
        var offset = 0
        while (offset < data.size) {
            val count = input.read(data, offset, data.size - offset)
            if (count < 0) return offset != 0
            offset += count
        }
        return true
    }

    private fun text(data: ByteArray, offset: Int, length: Int) =
        data.copyOfRange(offset, offset + length).takeWhile { it != 0.toByte() }
            .toByteArray().toString(Charsets.UTF_8)

    private fun octal(data: ByteArray, offset: Int, length: Int): Long =
        text(data, offset, length).trim().ifEmpty { "0" }.toLong(8)

    private fun copyExactly(input: InputStream, output: java.io.OutputStream, bytes: Long) {
        var remaining = bytes
        val buffer = ByteArray(64 * 1024)
        while (remaining > 0) {
            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            check(count >= 0) { "RootFS 包不完整" }
            output.write(buffer, 0, count)
            remaining -= count
        }
    }

    private fun skipFully(input: InputStream, bytes: Long) {
        var remaining = bytes
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) remaining -= skipped else check(input.read() >= 0) { "RootFS 包不完整" }.also { remaining-- }
        }
    }
}
