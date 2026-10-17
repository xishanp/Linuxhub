package com.doro.terminal.shell.linux

import org.junit.Assert.assertTrue
import org.junit.Test

class XfceWallpaperTest {
    @Test fun wallpaperScriptUpdatesEveryXfceBackdropProperty() {
        val script = DesktopCompatibility.wallpaperScript()
        assertTrue(script.contains("xfconf-query -c \"${'$'}channel\" -l"))
        assertTrue(script.contains("last-image"))
        assertTrue(script.contains("image-path"))
        assertTrue(script.contains("image-style"))
        assertTrue(script.contains("/usr/share/backgrounds/xfce/doro-ubuntu.png"))
    }

    @Test fun desktopSessionAppliesWallpaperInsideSessionBus() {
        val script = DesktopCompatibility.sessionScript(":0", "doro")
        assertTrue(script.contains("xfdesktop --reload"))
        assertTrue(script.contains("doro-apply-wallpaper"))
        assertTrue(script.contains("dbus-launch --exit-with-session"))
        assertTrue(script.contains("if [ ! -e \"/home/doro/.config/xfce4/.doro-wallpaper-v1\" ]"))
        assertTrue(script.contains("mkdir -p \"/home/doro/.config/xfce4\""))
    }
}
