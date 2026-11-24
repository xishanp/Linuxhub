package com.doro.terminal.shell.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopCompatibilityTest {
    @Test fun sessionScriptStartsSessionDbus() {
        val script = DesktopCompatibility.sessionScript(":0", "doro", GpuRenderMode.KGSL)
        assertTrue(script.contains("dbus-launch --exit-with-session"))
        assertFalse(script.contains("lotus/launch.fifo"))
        assertFalse(script.contains("gio launch"))
        assertTrue(script.contains("/usr/local/bin/doro-x11-control"))
        assertTrue(script.contains("clipboard-daemon"))
        assertTrue(script.contains("@linuxhub-clipboard-doro"))
        assertTrue(script.contains("doro_gpu_mode=software"))
        assertTrue(script.contains("doro_gpu_capability=kgsl"))
        assertTrue(script.contains("LIBGL_ALWAYS_SOFTWARE=1"))
        assertFalse(script.contains("MESA_LOADER_DRIVER_OVERRIDE=kgsl"))
        assertFalse(script.contains("GALLIUM_DRIVER=zink"))
    }

    @Test fun desktopLaunchQueueUsesValidShellSyntax() {
        val script = DesktopCompatibility.sessionScript(":0", "doro", GpuRenderMode.SOFTWARE)
        assertFalse(script.contains("&;"))
        assertFalse(script.contains("lotus-desktop-launch.log"))
        assertTrue(script.contains("doro-clipboardd.log 2>&1 & "))

        val validation = ProcessBuilder("/bin/sh", "-n", "-c", script)
            .redirectErrorStream(true)
            .start()
        val diagnostics = validation.inputStream.bufferedReader().readText()
        assertEquals(diagnostics, 0, validation.waitFor())
    }

    @Test fun sessionScriptForcesSimplifiedChineseEnvironment() {
        val script = DesktopCompatibility.sessionScript(":0", "doro", GpuRenderMode.KGSL)
        assertTrue(script.contains("export LANG=zh_CN.UTF-8 LC_ALL=zh_CN.UTF-8 LANGUAGE=zh_CN:zh"))
    }

    @Test fun offlineBootstrapContainsSimplifiedChineseTranslationPacks() {
        val manifest = java.io.File("src/main/assets/offline/bootstrap-packages.txt").readText()
        listOf(
            "language-pack-zh-hans_",
            "language-pack-zh-hans-base_",
            "language-pack-gnome-zh-hans_",
            "language-pack-gnome-zh-hans-base_",
        ).forEach { packagePrefix ->
            assertTrue(packagePrefix, manifest.contains(packagePrefix))
        }
    }

    @Test fun brokenTerminalMaterialIsRepairedWithoutTouchingOtherSettings() {
        val broken = "<property name=\"background-darkness\" type=\"double\" value=\"0.0\"/>\n" +
            "<property name=\"color-foreground\" type=\"string\" value=\"#f2f2f2\"/>\n"
        val repaired = DesktopCompatibility.repairBrokenTerminalMaterial(broken)
        assertTrue(repaired.contains("background-darkness\" type=\"double\" value=\"0.72\""))
        assertTrue(repaired.contains("color-foreground\" type=\"string\" value=\"#f2f2f2\""))
    }

    @Test fun legacyOpaqueWindowManagerIsRestoredToGraphiteTransparency() {
        val opaque = "<property name=\"frame_opacity\" type=\"int\" value=\"100\"/>\n" +
            "<property name=\"theme\" type=\"string\" value=\"WhiteSur-Dark\"/>\n"
        val repaired = DesktopCompatibility.repairOpaqueWindowOpacity(opaque)
        assertTrue(repaired.contains("frame_opacity\" type=\"int\" value=\"72\""))
        assertTrue(repaired.contains("theme\" type=\"string\" value=\"WhiteSur-Dark\""))
    }

    @Test fun knownXfceDefaultWallpaperIsRestoredForDynamicOutputs() {
        val original = """
            <property name="monitorbuiltin-headless" type="empty">
              <property name="workspace0" type="empty">
                <property name="last-image" type="string" value="/usr/share/backgrounds/xfce/xfce-shapes.svg"/>
              </property>
            </property>
        """.trimIndent()

        val repaired = DesktopCompatibility.repairDynamicWallpaper(original)

        assertTrue(repaired.contains("value=\"${DesktopCompatibility.DEFAULT_WALLPAPER}\""))
        assertFalse(repaired.contains("xfce-shapes.svg"))
    }

    @Test fun userSelectedWallpaperIsNeverOverwritten() {
        val original = """
            <property name="monitorbuiltin" type="empty">
              <property name="workspace0" type="empty">
                <property name="last-image" type="string" value="/home/doro/Pictures/my-wallpaper.png"/>
              </property>
            </property>
        """.trimIndent()

        val repaired = DesktopCompatibility.repairDynamicWallpaper(original)

        assertTrue(repaired == original)
    }

    @Test fun brokenTransparentClockPanelIsRepairedWithoutTouchingLayout() {
        val broken = "<property name=\"background-style\" type=\"uint\" value=\"0\"/>\n" +
            "      <property name=\"enter-opacity\" type=\"uint\" value=\"100\"/>\n" +
            "      <property name=\"plugin-ids\" type=\"array\">\n" +
            "        <value type=\"int\" value=\"12\"/>\n" +
            "      </property>\n"
        val repaired = DesktopCompatibility.repairBrokenPanelMaterial(broken)
        assertTrue(repaired.contains("background-style\" type=\"uint\" value=\"1\""))
        assertTrue(repaired.contains("background-rgba\" type=\"array\""))
        assertTrue(repaired.contains("<value type=\"int\" value=\"12\"/>"))
        assertTrue(repaired.contains("enter-opacity\" type=\"uint\" value=\"100\""))
    }

    @Test fun legacyGraphitePanelBackgroundIsRepairedToFullyTransparent() {
        val legacy = "<property name=\"background-rgba\" type=\"array\">\n" +
            "        <value type=\"double\" value=\"0.168627\"/>\n" +
            "        <value type=\"double\" value=\"0.180392\"/>\n" +
            "        <value type=\"double\" value=\"0.20000000000000001\"/>\n" +
            "        <value type=\"double\" value=\"0.71999999999999997\"/>\n" +
            "      </property>\n"
        val repaired = DesktopCompatibility.repairBrokenPanelMaterial(legacy)
        assertTrue(repaired.contains("<value type=\"double\" value=\"0.0\"/>"))
        assertTrue(!repaired.contains("0.168627"))
        assertTrue(!repaired.contains("0.71999999999999997"))
    }

    @Test fun desktopIconKeyReplacesAbsoluteIconWithoutDuplicates() {
        val original = "[Desktop Entry]\nName=Chrome\nIcon=/opt/google/chrome/product_logo_128.png\nExec=google-chrome %u\n"
        val updated = DesktopCompatibility.setDesktopKey(original, "Icon", "google-chrome")
        assertTrue(updated == "[Desktop Entry]\nName=Chrome\nIcon=google-chrome\nExec=google-chrome %u\n")
        assertTrue(DesktopCompatibility.setDesktopKey("Name=Chrome\nExec=google-chrome\n", "Icon", "google-chrome")
            .endsWith("Icon=google-chrome\n"))
    }

    @Test fun debPackagesUseTheLinuxHubInstallerWithoutOverwritingOtherMimeTypes() {
        val original = "[Default Applications]\ntext/html=google-chrome.desktop;\n\n[Added Associations]\napplication/x-deb=old-installer.desktop;\n"
        val updated = DesktopCompatibility.ensureDebMimeAssociations(original)
        assertTrue(updated.contains("text/html=google-chrome.desktop;"))
        assertTrue(updated.contains("application/vnd.debian.binary-package=linuxhub-deb-installer.desktop;"))
        assertTrue(updated.contains("application/x-deb=linuxhub-deb-installer.desktop;"))
        assertTrue(updated.contains("application/x-debian-package=linuxhub-deb-installer.desktop;"))
        assertTrue(updated.contains("[Added Associations]\napplication/x-deb=old-installer.desktop;"))
    }

    @Test fun chromeDesktopActionsKeepTheirLaunchArguments() {
        val original = """
            [Desktop Entry]
            Name=Google Chrome
            Exec=/usr/bin/google-chrome-stable %U
            Icon=/opt/google/chrome/product_logo_128.png

            [Desktop Action new-window]
            Name=New Window
            Exec=/usr/bin/google-chrome-stable

            [Desktop Action new-private-window]
            Name=New Incognito Window
            Exec=/usr/bin/google-chrome-stable --incognito
        """.trimIndent() + "\n"

        val patched = DesktopCompatibility.patchChromeDesktop(original)

        assertTrue(patched.contains("Exec=/usr/local/bin/doro-chrome %U"))
        assertTrue(patched.contains("[Desktop Action new-window]\nName=New Window\nExec=/usr/local/bin/doro-chrome\n"))
        assertTrue(
            patched.contains(
                "[Desktop Action new-private-window]\nName=New Incognito Window\nExec=/usr/local/bin/doro-chrome --incognito\n",
            ),
        )
        assertTrue(patched.contains("Icon=google-chrome"))
    }

    @Test fun offlineRuntimePackagesStayVersionAlignedWithGccAndNcurses() {
        val manifest = java.io.File("src/main/assets/offline/bootstrap-packages.txt").readText()
        assertTrue(manifest.contains("gcc-14-base_14.2.0-4ubuntu2~24.04.1_arm64.deb"))
        assertTrue(manifest.contains("libgcc-s1_14.2.0-4ubuntu2~24.04.1_arm64.deb"))
        assertTrue(manifest.contains("libstdc++6_14.2.0-4ubuntu2~24.04.1_arm64.deb"))
        assertTrue(manifest.contains("libncursesw6_6.4+20240113-1ubuntu2.2_arm64.deb"))
    }

    @Test fun desktopLaunchersOnlyUseApplicationWrappers() {
        val chrome = DesktopCompatibility.patchDesktop("Exec=/usr/local/bin/doro-chrome %U")
        val legacyChrome = DesktopCompatibility.patchDesktop(
            "Exec=/usr/local/bin/doro-gpu-run /usr/local/bin/doro-chrome %U",
        )
        val thunar = DesktopCompatibility.patchDesktop("Exec=/usr/local/bin/doro-gpu-run thunar %F")
        assertTrue(chrome == "Exec=/usr/local/bin/doro-chrome %U")
        assertTrue(legacyChrome == chrome)
        assertTrue(thunar == "Exec=thunar %F")
        assertTrue(DesktopCompatibility.patchDesktop(thunar) == thunar)
    }

    @Test fun interactiveProfileKeepsTheDesktopOnSoftwareRendering() {
        val profile = GpuRuntimeEnvironment.profileScript()
        assertTrue(profile.contains("export GALLIUM_DRIVER=llvmpipe LIBGL_ALWAYS_SOFTWARE=1"))
        assertTrue(profile.contains("unset MESA_LOADER_DRIVER_OVERRIDE"))
        assertFalse(profile.contains("GALLIUM_DRIVER=kgsl"))
        assertFalse(profile.contains("VK_ICD_FILENAMES="))
    }

    @Test fun applicationLauncherUsesHighestAdrenoTierWithFallbacks() {
        val launcher = java.io.File(
            "src/main/assets/${GpuRuntimeEnvironment.applicationLauncherAsset}",
        ).readText()
        assertTrue(launcher.contains("cat /etc/doro-gpu-mode"))
        assertTrue(launcher.contains("cat /etc/doro-gpu-enabled"))
        assertTrue(launcher.contains("MESA_LOADER_DRIVER_OVERRIDE=zink GALLIUM_DRIVER=zink"))
        assertTrue(launcher.contains("MESA_LOADER_DRIVER_OVERRIDE=kgsl GALLIUM_DRIVER=kgsl"))
        assertTrue(launcher.contains("export DORO_GPU_RUNTIME=1"))
        assertTrue(launcher.contains("export GALLIUM_DRIVER=llvmpipe LIBGL_ALWAYS_SOFTWARE=1"))
        val validation = ProcessBuilder("/bin/sh", "-n", "-c", launcher)
            .redirectErrorStream(true)
            .start()
        val diagnostics = validation.inputStream.bufferedReader().readText()
        assertEquals(diagnostics, 0, validation.waitFor())
    }

    @Test fun everyUserApplicationGetsTheHardwareLauncherExactlyOnce() {
        val launcher = DesktopCompatibility.patchDesktop("Exec=mousepad %U\n")
        val wrapped = DesktopCompatibility.wrapApplicationWithGpu(launcher, "mousepad.desktop")
        assertEquals("Exec=/usr/local/bin/doro-gpu-run mousepad %U\n", wrapped)
        assertEquals(wrapped, DesktopCompatibility.wrapApplicationWithGpu(wrapped, "mousepad.desktop"))

        val chrome = DesktopCompatibility.wrapApplicationWithGpu(
            "Exec=/usr/local/bin/doro-chrome %U",
            "google-chrome.desktop",
        )
        assertEquals("Exec=/usr/local/bin/doro-chrome %U", chrome)
    }

    @Test fun desktopShellComponentsStayOnSoftwareRendering() {
        listOf(
            "xfce4-session.desktop",
            "xfwm4.desktop",
            "xfdesktop.desktop",
            "xfce4-panel.desktop",
            "panel-desktop-handler.desktop",
            "panel-preferences.desktop",
            "plank.desktop",
        ).forEach { name ->
            val desktop = "Exec=xfce4-panel --add=launcher %u"
            assertEquals(desktop, DesktopCompatibility.wrapApplicationWithGpu(desktop, name))
        }
    }

    @Test fun kgslCapabilityNeverAcceleratesTheDesktopSession() {
        val script = DesktopCompatibility.sessionScript(":0", "doro", GpuRenderMode.KGSL)
        assertTrue(script.contains("doro_gpu_mode=software"))
        assertTrue(script.contains("doro_gpu_capability=kgsl"))
        assertTrue(script.contains("GALLIUM_DRIVER=llvmpipe LIBGL_ALWAYS_SOFTWARE=1"))
        assertTrue(script.contains("unset MESA_LOADER_DRIVER_OVERRIDE"))
        assertFalse(script.contains("timeout 8 glxinfo -B"))
    }

    @Test fun softwareSessionNeverExportsHardwareDrivers() {
        val script = DesktopCompatibility.sessionScript(":0", "doro", GpuRenderMode.SOFTWARE)
        assertTrue(script.contains("doro_gpu_mode=software"))
        assertTrue(script.contains("GALLIUM_DRIVER=llvmpipe LIBGL_ALWAYS_SOFTWARE=1"))
        assertFalse(script.contains("MESA_LOADER_DRIVER_OVERRIDE=kgsl"))
    }
}
