package com.doro.terminal.shell.linux

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChineseTranslationsTest {
    private val archive = File("src/main/assets/desktop/zh_CN-translations.tar")

    @Test fun translationBundleContainsEveryDesktopCatalog() {
        assertTrue(archive.isFile)
        val names = archive.readBytes().toString(Charsets.ISO_8859_1)
        listOf(
            "usr/share/locale/zh_CN/LC_MESSAGES/xfce4-terminal.mo",
            "usr/share/locale/zh_CN/LC_MESSAGES/thunar.mo",
            "usr/share/locale/zh_CN/LC_MESSAGES/xfce4-panel.mo",
            "usr/share/locale/zh_CN/LC_MESSAGES/xfwm4.mo",
            "usr/share/locale/zh_CN/LC_MESSAGES/xfdesktop.mo",
            "usr/share/locale/zh_CN/LC_MESSAGES/xfce4-settings.mo",
            "usr/share/locale/zh_CN/LC_MESSAGES/libxfce4ui.mo",
        ).forEach { path -> assertTrue(path, names.contains(path)) }
    }

    @Test fun translationBundleIsPinnedBySha256() {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(archive.readBytes())
            .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
        assertEquals("2f537c9e9653331c05148964b08c3d3f16b26de03e96d978da698dd4b80fe119", digest)
    }
}
