package com.doro.terminal.shell.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinuxHubUserDirectoriesTest {
    @Test
    fun exposesEveryStandardXdgDirectory() {
        assertEquals(
            listOf(
                "Desktop",
                "Documents",
                "Downloads",
                "Music",
                "Pictures",
                "Public",
                "Templates",
                "Videos",
            ),
            LinuxHubUserDirectories.directories,
        )
    }

    @Test
    fun rendersDesktopPathUsedByNautilus() {
        val content = LinuxHubUserDirectories.userDirsFile()
        assertTrue(content.contains("XDG_DESKTOP_DIR=\"\$HOME/Desktop\""))
        assertTrue(content.contains("XDG_DOCUMENTS_DIR=\"\$HOME/Documents\""))
        assertTrue(content.contains("\n"))
        assertFalse(content.contains("\\n"))
        assertEquals("zh_CN", LinuxHubUserDirectories.locale)
    }

    @Test
    fun bookmarksPointAtExistingLocalizedDirectories() {
        val content = LinuxHubUserDirectories.bookmarksFile("qwer")
        assertTrue(content.contains("file:///home/qwer/Desktop 桌面"))
        assertTrue(content.contains("file:///home/qwer/Documents 文档"))
        assertTrue(content.contains("file:///home/qwer/Downloads 下载"))
        assertFalse(content.contains("\\n"))
    }
}
