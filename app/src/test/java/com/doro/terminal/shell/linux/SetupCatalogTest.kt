package com.doro.terminal.shell.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupCatalogTest {
    @Test
    fun simplifiedChineseDesktopTranslationsAreBuiltIn() {
        val defaults = SetupCatalog.resolveSelection(emptySet())
        assertEquals(
            listOf("locales", "language-pack-zh-hans", "language-pack-gnome-zh-hans", "fonts-noto-cjk"),
            InstallComponent.CHINESE.packages,
        )
        assertTrue(InstallComponent.CHINESE in defaults)
    }

    @Test
    fun bundledAppsAreNotShownInSetup() {
        val visible = SetupCatalog.optionalComponents
        assertFalse(InstallComponent.WECHAT in visible)
        assertFalse(InstallComponent.CHROME in visible)
    }

    @Test
    fun bundledDesktopIsNotShownInSetup() {
        val visible = SetupCatalog.optionalComponents
        assertFalse(InstallComponent.DESKTOP in visible)
        assertFalse(InstallComponent.GNOME in visible)
        assertFalse(InstallComponent.X11 in visible)
        assertFalse(InstallComponent.PULSEAUDIO in visible)
        assertFalse(InstallComponent.FILE_MANAGER in visible)
    }

    @Test
    fun chromeIsInstalledByTheDefaultGnomeBoot() {
        assertTrue(InstallComponent.CHROME in SetupCatalog.resolveSelection(emptySet()))
    }

    @Test
    fun developmentAndAiEnvironmentsAreOnDemandOnly() {
        val defaults = SetupCatalog.resolveSelection(emptySet())
        listOf(
            InstallComponent.GIT,
            InstallComponent.PYTHON,
            InstallComponent.NODE,
            InstallComponent.GCC,
            InstallComponent.CMAKE,
            InstallComponent.JAVA,
            InstallComponent.RUST,
            InstallComponent.GO,
            InstallComponent.PYTHON_AI,
            InstallComponent.JUPYTER
        ).forEach { component ->
            assertFalse("$component must not be installed by default", component in defaults)
            assertFalse("$component must not be shown in setup", component in SetupCatalog.optionalComponents)
        }
    }
}
