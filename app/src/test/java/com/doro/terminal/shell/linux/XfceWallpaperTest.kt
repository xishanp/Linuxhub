package com.doro.terminal.shell.linux

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class XfceWallpaperTest {
    @Test fun staticProfileSetsWallpaperForKnownXfceMonitorNames() {
        val file = File("src/main/assets/${DesktopDefaults.ASSET_ROOT}/xfce4-desktop.xml")
        assertTrue(file.isFile)
        val desktop = file.readText()
        assertTrue(desktop.contains("name=\"monitor0\""))
        assertTrue(desktop.contains("name=\"monitorbuiltin\""))
        assertTrue(desktop.contains("image-style\" type=\"int\" value=\"5\""))
        assertTrue(desktop.contains("/usr/share/backgrounds/xfce/doro-ubuntu.png"))
    }

    @Test fun dynamicMonitorInheritsPresetWithoutOverwritingCustomWallpaper() {
        val guard = File(
            "src/main/assets/${DesktopDefaults.ASSET_ROOT}/doro-wallpaper-guard.sh",
        )
        assertTrue(guard.isFile)
        val script = guard.readText()
        assertTrue(script.contains("xfconf-query -c xfce4-desktop -l"))
        assertTrue(script.contains("last-image$"))
        assertTrue(script.contains("xfce-shapes.svg"))
        assertTrue(script.contains("xfconf-query -c xfce4-desktop -p \"\$key\" -s \"\$wallpaper\""))
        assertTrue(DesktopCompatibility.sessionScript(":0", "doro", GpuRenderMode.KGSL)
            .contains("/usr/local/bin/doro-wallpaper-guard"))
    }
}
