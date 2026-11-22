package com.doro.terminal.shell.linux

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Ubuntu 24.04 uses a merged-/usr layout. Package extraction can replace the
 * top-level symlinks with real directories, which leaves ELF interpreters such
 * as /lib/ld-linux-aarch64.so.1 unreachable and makes every dynamically linked
 * command fail with the misleading "not found" error.
 */
internal object MergedUsrLayout {
    private val links = listOf(
        "bin" to "usr/bin",
        "sbin" to "usr/sbin",
        "lib" to "usr/lib",
    )

    fun repair(rootfs: File): Int {
        var moved = 0
        links.forEach { (name, target) ->
            val targetDirectory = File(rootfs, target)
            check(targetDirectory.isDirectory || targetDirectory.mkdirs()) {
                "无法创建合并 /usr 目录：${targetDirectory.path}"
            }
            val link = File(rootfs, name)
            val path = link.toPath()
            if (Files.isSymbolicLink(path)) {
                if (Files.readSymbolicLink(path) == Paths.get(target)) return@forEach
                check(link.delete()) { "无法替换合并 /usr 链接：${link.path}" }
            } else if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                check(Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    "合并 /usr 路径冲突：${link.path}"
                }
                moved += mergeContents(link, targetDirectory)
                check(link.delete()) { "无法删除已合并目录：${link.path}" }
            }
            Files.createSymbolicLink(path, Paths.get(target))
        }
        return moved
    }

    private fun mergeContents(source: File, destination: File): Int {
        destination.mkdirs()
        var moved = 0
        source.listFiles().orEmpty().forEach { child ->
            val target = File(destination, child.name)
            val childPath = child.toPath()
            val targetPath = target.toPath()
            when {
                !Files.exists(targetPath, LinkOption.NOFOLLOW_LINKS) -> {
                    check(child.renameTo(target)) { "合并 /usr 文件失败：${child.path}" }
                    moved++
                }
                Files.isDirectory(childPath, LinkOption.NOFOLLOW_LINKS) &&
                    Files.isDirectory(targetPath, LinkOption.NOFOLLOW_LINKS) -> {
                    moved += mergeContents(child, target)
                    check(child.delete()) { "无法删除已合并目录：${child.path}" }
                }
                else -> {
                    // Keep both copies instead of silently discarding whichever
                    // one happened to be unpacked last.
                    val conflicts = File(destination, ".doro-merged-usr-conflicts")
                    val backup = File(conflicts, child.relativeTo(source).path)
                    backup.parentFile?.mkdirs()
                    if (!backup.exists()) {
                        check(child.renameTo(backup)) { "无法保留合并 /usr 冲突文件：${child.path}" }
                        moved++
                    } else {
                        check(child.deleteRecursively()) { "无法清理重复冲突文件：${child.path}" }
                    }
                }
            }
        }
        return moved
    }
}
