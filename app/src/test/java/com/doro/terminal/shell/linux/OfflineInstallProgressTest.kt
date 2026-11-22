package com.doro.terminal.shell.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OfflineInstallProgressTest {
    @Test
    fun reportsPackageBeingInstalled() {
        val action = OfflineInstallProgress.parse(
            "Unpacking gnome-shell (46.0-0ubuntu6~24.04.17) ...",
        )

        assertEquals("gnome-shell", action?.packageName)
        assertEquals(OfflineInstallAction.Phase.INSTALLING, action?.phase)
        assertEquals("gnome-shell", action?.packageKey)
        assertEquals(true, action?.countsAsProcessedPackage)
        assertEquals(
            "正在安装 gnome-shell（93/725）",
            OfflineInstallProgress.message(action, 725, 93),
        )
    }

    @Test
    fun reportsPackageBeingConfigured() {
        val action = OfflineInstallProgress.parse(
            "Setting up gnome-shell:arm64 (46.0-0ubuntu6~24.04.17) ...",
        )

        assertEquals("gnome-shell:arm64", action?.packageName)
        assertEquals(OfflineInstallAction.Phase.CONFIGURING, action?.phase)
        assertEquals("gnome-shell", action?.packageKey)
        assertEquals(true, action?.countsAsProcessedPackage)
        assertEquals(
            "正在配置 gnome-shell:arm64（93/725）",
            OfflineInstallProgress.message(action, 725, 93),
        )
    }

    @Test
    fun triggerRefreshDoesNotIncreasePackageCount() {
        val action = OfflineInstallProgress.parse(
            "Processing triggers for libc-bin:arm64 (2.39-0ubuntu8.6) ...",
        )

        assertEquals(OfflineInstallAction.Phase.REFRESHING, action?.phase)
        assertEquals(false, action?.countsAsProcessedPackage)
    }

    @Test
    fun ignoresUnrelatedOutput() {
        assertNull(OfflineInstallProgress.parse("Fetched 0 B in 0s"))
        assertEquals(
            "正在配置桌面和系统组件（725/725）",
            OfflineInstallProgress.message(null, 725, 725),
        )
    }
}
