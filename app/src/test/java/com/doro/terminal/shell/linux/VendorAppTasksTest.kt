package com.doro.terminal.shell.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class VendorAppTasksTest {
    @Test
    fun parsesOneVisibleTaskPerApplication() {
        val tasks = VendorAppTasks.parse(
            "libreoffice\tLibreOffice\tlibreoffice.deb libreoffice-gnome.deb\n" +
                "chrome\tGoogle Chrome\tgoogle-chrome.deb\n",
        )

        assertEquals(listOf("libreoffice", "chrome"), tasks.map(VendorAppTask::key))
        assertEquals(listOf("libreoffice.deb", "libreoffice-gnome.deb"), tasks[0].packageFiles)
        assertEquals("Google Chrome", tasks[1].label)
    }

    @Test
    fun rejectsPackagesThatEscapeTheOfflineDirectory() {
        assertThrows(IllegalArgumentException::class.java) {
            VendorAppTasks.parse("chrome\tChrome\t../chrome.deb\n")
        }
    }

    @Test
    fun parsesSharedDependencies() {
        assertEquals(
            listOf("libreoffice-core.deb", "libgtk-3-0.deb"),
            VendorAppTasks.parseDependencies("libreoffice-core.deb\nlibgtk-3-0.deb\n"),
        )
    }

    @Test
    fun installFailureSummaryKeepsTheRealErrorInsteadOfProgress() {
        val message = """
            展开 tpm-udev_0.6ubuntu1_all
            展开 libtss2-mu-4.0.1-0t64_4.0.1-7.1ubuntu5.1_arm64
            /bin/sh: 1: /usr/bin/dpkg-deb: not found
        """.trimIndent()

        assertEquals(
            "/bin/sh: 1: /usr/bin/dpkg-deb: not found",
            summarizeInstallFailure(message, timedOut = false),
        )
    }

    @Test
    fun installFailureSummaryFallsBackToLastLines() {
        val message = "第一行\n第二行\n第三行"

        assertEquals("第一行\n第二行\n第三行", summarizeInstallFailure(message, timedOut = false))
    }
}
