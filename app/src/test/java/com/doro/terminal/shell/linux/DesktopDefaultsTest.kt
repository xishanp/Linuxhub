package com.doro.terminal.shell.linux

import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopDefaultsTest {
    private fun asset(path: String): String {
        val file = File("src/main/assets/$path")
        assertTrue("missing desktop asset: $path", file.isFile)
        return file.readText()
    }

    private fun materialCss(): String = asset("${DesktopDefaults.ASSET_ROOT}/linuxhub-material.css")

    @Test fun applicationLauncherDoesNotLeakGnomeShellLibraryPath() {
        val launcher = asset("${DesktopDefaults.ASSET_ROOT}/doro-gpu-run.sh")
        assertTrue(
            "application launchers must not inherit GNOME Shell's private LD_LIBRARY_PATH",
            launcher.contains("unset LD_LIBRARY_PATH"),
        )
    }

    @Test fun gtkSurfacesUseOneGraphitePalette() {
        val css = materialCss()
        assertTrue(css.contains("window.background:not(.xfce4-panel)"))
        assertTrue(css.contains("dialog.background"))
        assertTrue(css.contains("rgba(43, 46, 51, 0.72)"))
        val loader = asset("${DesktopDefaults.ASSET_ROOT}/gtk.css")
        assertTrue(loader.contains("@import url(\"linuxhub-material.css\")"))
        assertTrue(loader.contains("@import url(\"gtk-user.css\")"))
        assertTrue(DesktopDefaults.managedHomeProfileAssets.any { it.destination == ".config/gtk-3.0/gtk.css" })
        assertTrue(DesktopDefaults.managedHomeProfileAssets.any { it.destination == ".config/gtk-4.0/gtk.css" })
        assertTrue(DesktopDefaults.homeProfileAssets.any { it.destination == ".config/gtk-3.0/gtk-user.css" })
        assertTrue(DesktopDefaults.homeProfileAssets.any { it.destination == ".config/gtk-4.0/gtk-user.css" })
    }

    @Test fun everyManifestAssetExists() {
        (DesktopDefaults.homeProfileAssets +
            DesktopDefaults.managedHomeProfileAssets +
            DesktopDefaults.applicationAssets +
            DesktopDefaults.systemProfileAssets +
            DesktopDefaults.executableAssets).forEach { entry ->
            assertTrue(entry.asset, File("src/main/assets/${entry.asset}").isFile)
        }
    }

    @Test fun agentX11ControlAssetsCoverWindowsAndClipboard() {
        val helper = asset("${DesktopDefaults.ASSET_ROOT}/doro-x11-control.py")
        listOf("windows", "activate", "close", "wait", "clipboard", "clipboard-daemon").forEach {
            assertTrue(it, helper.contains(it))
        }
        assertTrue(helper.contains("_NET_ACTIVE_WINDOW"))
        assertTrue(helper.contains("_NET_CLOSE_WINDOW"))
        assertTrue(helper.contains("libX11.so.6"))
        assertTrue(DesktopDefaults.executableAssets.any { it.destination == "doro-x11-control" })
    }

    @Test fun panelUsesAppleMenuPagerAndUserLabel() {
        val panel = asset("${DesktopDefaults.ASSET_ROOT}/xfce4-panel.xml")
        assertTrue(panel.contains("value=\"applicationsmenu\""))
        assertTrue(panel.contains("value=\"tasklist\""))
        assertTrue(panel.contains("value=\"clock\""))
        assertTrue(panel.contains("value=\"pulseaudio\""))
        assertTrue(panel.contains("value=\"pager\""))
        assertTrue(panel.contains("value=\"genmon\""))
        assertTrue(panel.contains("show-button-title\" type=\"bool\" value=\"false\""))
        assertTrue(panel.contains("button-icon\" type=\"string\" value=\"start-here\""))
        assertTrue(panel.contains("name=\"command\" type=\"string\" value=\"/usr/local/bin/doro-user-label\""))
        assertTrue(panel.contains("name=\"command\" type=\"string\" value=\"/usr/local/bin/doro-battery-status\""))
        assertTrue(panel.contains("name=\"battery-status\" type=\"string\" value=\"/usr/local/bin/doro-battery-status\"") || panel.contains("name=\"command\" type=\"string\" value=\"/usr/local/bin/doro-battery-status\""))
        assertTrue(panel.contains("background-style\" type=\"uint\" value=\"1\""))
        assertTrue(panel.contains("background-rgba\" type=\"array\""))
        assertFalse(panel.contains("value=\"Applications\""))
        val separator = panel.substringAfter("name=\"plugin-3\"").substringBefore("name=\"plugin-6\"")
        assertTrue(separator.contains("value=\"separator\""))
        assertTrue(separator.contains("style\" type=\"uint\" value=\"0\""))
        assertTrue(separator.contains("expand\" type=\"bool\" value=\"true\""))
        assertFalse(separator.contains("style\" type=\"uint\" value=\"1\""))
        assertFalse(panel.contains("value=\"actions\""))
    }

    @Test fun bothTopPanelsAreFullyTransparent() {
        val panel = asset("${DesktopDefaults.ASSET_ROOT}/xfce4-panel.xml")
        val panel1 = panel.substringAfter("name=\"panel-1\"").substringBefore("name=\"panel-2\"")
        val panel2 = panel.substringAfter("name=\"panel-2\"").substringBefore("name=\"plugins\"")
        listOf(panel1, panel2).forEach { block ->
            assertTrue(block.contains("background-style\" type=\"uint\" value=\"1\""))
            assertTrue(block.contains("background-rgba\" type=\"array\""))
            assertTrue(block.split("<value type=\"double\" value=\"0.0\"/>").size - 1 >= 4)
            assertFalse(block.contains("0.168627"))
            assertFalse(block.contains("0.180392"))
            assertFalse(block.contains("<value type=\"double\" value=\"0.72\"/>"))
        }
        assertTrue(panel2.contains("p=9;x=0;y=0"))
        assertTrue(panel2.contains("enable-struts\" type=\"bool\" value=\"false\""))
        assertTrue(panel2.contains("<property name=\"plugin-ids\" type=\"array\">\n        <value type=\"int\" value=\"12\"/>"))
        assertTrue(panel.contains("name=\"plugin-12\" type=\"string\" value=\"clock\""))
        val css = materialCss()
        assertTrue(css.contains("#XfcePanelWindow #clock"))
        assertTrue(css.contains("background-color: transparent"))
    }

    @Test fun desktopProvidesFourWorkspaceSwitchers() {
        val xfwm = asset("${DesktopDefaults.ASSET_ROOT}/xfwm4.xml")
        assertTrue(xfwm.contains("workspace_count\" type=\"int\" value=\"4\""))
        (1..4).forEach { assertTrue(xfwm.contains("桌面 $it")) }
        val panel = asset("${DesktopDefaults.ASSET_ROOT}/xfce4-panel.xml")
        val pager = panel.substringAfter("name=\"plugin-6\"").substringBefore("name=\"plugin-7\"")
        assertTrue(pager.contains("rows\" type=\"uint\" value=\"1\""))
        assertTrue(pager.contains("miniature-view\" type=\"bool\" value=\"true\""))
    }

    @Test fun compositorAndTerminalUseTranslucentGraphiteDefaults() {
        val xfwm = asset("${DesktopDefaults.ASSET_ROOT}/xfwm4.xml")
        assertTrue(xfwm.contains("use_compositing\" type=\"bool\" value=\"true\""))
        assertTrue(xfwm.contains("frame_opacity\" type=\"int\" value=\"72\""))
        assertTrue(xfwm.contains("inactive_opacity\" type=\"int\" value=\"100\""))
        assertTrue(xfwm.contains("move_opacity\" type=\"int\" value=\"100\""))
        assertTrue(xfwm.contains("resize_opacity\" type=\"int\" value=\"100\""))
        assertTrue(xfwm.contains("popup_opacity\" type=\"int\" value=\"100\""))
        val terminal = asset("${DesktopDefaults.ASSET_ROOT}/xfce4-terminal.xml")
        assertTrue(terminal.contains("misc-menubar-default\" type=\"bool\" value=\"false\""))
        assertTrue(terminal.contains("background-mode\" type=\"string\" value=\"TERMINAL_BACKGROUND_TRANSPARENT\""))
        assertTrue(terminal.contains("background-darkness\" type=\"double\" value=\"0.72\""))
        assertTrue(terminal.contains("color-background\" type=\"string\" value=\"#2b2e33\""))
        assertTrue(terminal.contains("color-foreground\" type=\"string\" value=\"#f2f2f2\""))
    }

    @Test fun everyDesktopSurfaceUsesOneGraphiteLayer() {
        val css = materialCss()
        assertTrue(css.contains("@define-color doro_surface rgba(43, 46, 51, 0.72)"))
        listOf(
            "window.background:not(.xfce4-panel)",
            "dialog.background",
            "window.background.xfce4-terminal",
            "window.background.xfce4-terminal menubar",
            "headerbar",
            "window menubar",
            "toolbar",
            "notebook > header",
            "terminal-screen-container",
            "vte-terminal",
            "popover.background",
        ).forEach { selector -> assertTrue(selector, css.contains(selector)) }
        assertTrue(css.contains("window.background.xfce4-terminal {\n  background-color: transparent"))
        assertTrue(css.contains("window.background.xfce4-terminal > box.background,\n" +
            "window.background.xfce4-terminal > box.background > menubar > menuitem {\n" +
            "  background-color: transparent"))
        assertTrue(css.contains("window.background.xfce4-terminal > box.background > menubar {\n" +
            "  background-color: @doro_surface"))
        assertFalse(css.contains("dialog.background,\nwindow.xfce4-terminal"))
    }

    @Test fun thunarUsesTranslucentGraphiteSurfaces() {
        val css = materialCss()
        assertTrue(css.contains("window.background:not(.xfce4-panel)"))
        assertTrue(css.contains("dialog.background"))
        assertTrue(css.contains("window.background.thunar {"))
        assertTrue(css.contains("rgba(43, 46, 51, 0.72)"))
        assertTrue(css.contains("window.thunar .standard-view"))
        assertTrue(css.contains("window.thunar .shortcuts-pane"))
    }

    @Test fun themeUsesCompleteTelaCircleAsDefault() {
        val settings = asset("${DesktopDefaults.ASSET_ROOT}/xsettings.xml")
        assertTrue(settings.contains("IconThemeName\" type=\"string\" value=\"Tela-circle\""))
        assertTrue(settings.contains("ThemeName\" type=\"string\" value=\"WhiteSur-Dark\""))
        val gtkSettings = asset("${DesktopDefaults.ASSET_ROOT}/gtk-settings.ini")
        assertTrue(gtkSettings.contains("gtk-icon-theme-name=Tela-circle"))
    }

    @Test fun telaCircleArchiveContainsEveryPreOverlayRequirement() {
        val archive = File("src/main/assets/desktop/tela-circle.tar")
        val tar = GZIPInputStream(archive.inputStream()).use {
            it.readBytes().toString(Charsets.ISO_8859_1)
        }
        listOf(
            "icons/Tela-circle/index.theme",
            "icons/Tela-circle/scalable/apps/google-chrome.svg",
            "icons/Tela-circle/22/places/folder.svg",
            "icons/Tela-circle/22/panel/start-here.svg",
        ).forEach { path ->
            assertTrue("Tela Circle archive missing $path", tar.contains(path))
        }
    }

    @Test fun linuxHubIconsOverlayTheCompleteTelaCircleTheme() {
        val archive = File("src/main/assets/desktop/linuxhub-icons.bin")
        assertTrue(archive.isFile)
        val tar = GZIPInputStream(archive.inputStream()).use { it.readBytes().toString(Charsets.ISO_8859_1) }
        assertFalse(tar.contains("Mignon-pastel"))
        assertFalse(tar.contains("LinuxHub-Pixel/index.theme"))
        assertTrue(tar.contains("icons/Tela-circle/scalable/apps/start-here.svg"))
        assertTrue(tar.contains("icons/Tela-circle/22/panel/start-here.svg"))
        assertTrue(tar.contains("icons/Tela-circle/scalable/places/user-home.svg"))
        assertTrue(tar.contains("icons/Tela-circle/scalable/places/folder.svg"))
        assertTrue(tar.contains("icons/Tela-circle/scalable/mimetypes/application-pdf.svg"))
        assertTrue(tar.contains("icons/Tela-circle/scalable/mimetypes/image-x-generic.svg"))
    }

    @Test fun staticDesktopProfileHidesEveryDefaultObject() {
        val desktop = asset("${DesktopDefaults.ASSET_ROOT}/xfce4-desktop.xml")
        listOf(
            "show-home",
            "show-filesystem",
            "show-trash",
            "show-removable",
            "show-device-fixed",
            "show-device-removable",
            "show-unknown-removable",
            "show-network-removable",
        ).forEach { property ->
            assertTrue(property, desktop.contains("name=\"$property\" type=\"bool\" value=\"false\""))
        }
    }

    @Test fun dockUsesHomeTerminalChromeOnly() {
        val script = asset("${DesktopDefaults.ASSET_ROOT}/doro-start-plank.sh")
        val home = script.indexOf("home.dockitem")
        val terminal = script.indexOf("terminal.dockitem")
        val chrome = script.indexOf("google-chrome.dockitem")
        assertTrue(home in 0 until terminal)
        assertTrue(terminal in 0 until chrome)
        assertTrue(script.contains("managed_items=\"['home.dockitem', 'terminal.dockitem', 'google-chrome.dockitem']\""))
        assertTrue(script.contains("dock-items \"${'$'}managed_items\""))
        assertTrue(script.contains("doro-layout-v2"))
        assertFalse(script.contains("> \"${'$'}launchers/spark-store.dockitem\""))
        assertFalse(script.contains("/usr/share/applications/doro-app-grid.desktop"))
        assertFalse(script.contains("> \"${'$'}launchers/app-grid.dockitem\""))
        assertTrue(script.contains("apply_key theme \"'LinuxHub-Dock'\""))
        val dockTheme = asset("${DesktopDefaults.ASSET_ROOT}/plank-dock.theme")
        assertTrue(dockTheme.contains("FillStartColor=43;;46;;51;;184"))
        assertTrue(dockTheme.contains("FillEndColor=43;;46;;51;;184"))
        assertTrue(dockTheme.contains("InnerStrokeColor=0;;0;;0;;0"))
        assertFalse(dockTheme.contains("FillStartColor=0;;0;;0;;255"))
        assertTrue(script.contains("apply_key hide-mode \"'none'\""))
        assertTrue(script.contains("apply_key lock-items false"))
        assertTrue(script.contains("apply_key pinned-only false"))
        assertTrue(script.contains("apply_key zoom-enabled true"))
        assertTrue(script.contains("apply_key zoom-percent 200"))
        assertTrue(script.contains("linuxhub-plank.lock"))
    }

    @Test fun iconArchiveExtractsAllRequiredFiles() {
        val archive = File("src/main/assets/desktop/linuxhub-icons.bin")
        val destination = Files.createTempDirectory("linuxhub-icons-test").toFile()
        try {
            archive.inputStream().use { TarGzExtractor.extract(it, destination) }
            assertTrue(File(destination, "icons/Tela-circle/scalable/apps/start-here.svg").isFile)
            assertTrue(File(destination, "icons/Tela-circle/scalable/places/folder.svg").isFile)
            assertTrue(File(destination, "icons/Tela-circle/22/panel/start-here.svg").isFile)
            assertTrue(File(destination, "icons/Tela-circle/scalable/mimetypes/application-pdf.svg").isFile)
        } finally {
            destination.deleteRecursively()
        }
    }

    @Test fun bundledLaunchersResolveThroughIconTheme() {
        assertTrue(asset("${DesktopDefaults.ASSET_ROOT}/doro-terminal.desktop").contains("Icon=utilities-terminal"))
        assertTrue(asset("${DesktopDefaults.ASSET_ROOT}/doro-terminal.desktop").contains("--hide-menubar"))
        assertTrue(asset("${DesktopDefaults.ASSET_ROOT}/doro-home.desktop").contains("Icon=user-home"))
        assertTrue(asset("${DesktopDefaults.ASSET_ROOT}/google-chrome.desktop").contains("Icon=google-chrome"))
        assertTrue(asset("${DesktopDefaults.ASSET_ROOT}/mimeapps.list").contains("x-scheme-handler/https=google-chrome.desktop"))
    }

    @Test fun debPackagesOpenWithLinuxHubInstaller() {
        val desktop = asset("${DesktopDefaults.ASSET_ROOT}/linuxhub-deb-installer.desktop")
        assertTrue(desktop.contains("Exec=/usr/local/bin/linuxhub-deb-installer %f"))
        assertTrue(desktop.contains("TryExec=/usr/local/bin/linuxhub-deb-installer"))
        assertTrue(desktop.contains("Terminal=true"))
        assertTrue(desktop.contains("application/vnd.debian.binary-package"))
        assertTrue(desktop.contains("application/x-deb"))
        assertTrue(desktop.contains("application/x-debian-package"))
        val mimeapps = asset("${DesktopDefaults.ASSET_ROOT}/mimeapps.list")
        assertTrue(mimeapps.contains("application/vnd.debian.binary-package=linuxhub-deb-installer.desktop;"))
        assertTrue(mimeapps.contains("application/x-debian-package=linuxhub-deb-installer.desktop;"))
        val installer = asset("${DesktopDefaults.ASSET_ROOT}/linuxhub-deb-installer.sh")
        assertTrue(installer.contains("dpkg-deb -f"))
        assertTrue(installer.contains("sudo apt-get install -y \"${'$'}package\""))
        assertTrue(installer.contains("/usr/local/bin/doro-refresh-launchers"))
        assertTrue(installer.contains("安装失败"))
        val refresher = DesktopDefaults.executableAssets.single {
            it.destination == "doro-refresh-launchers"
        }
        assertTrue(refresher.executable)
        val refresherScript = asset(refresher.asset)
        assertTrue(refresherScript.contains("/usr/local/bin/doro-gpu-run"))
        assertTrue(refresherScript.contains("wechat.desktop"))
        assertTrue(refresherScript.contains("google-chrome.desktop"))
        assertTrue(refresherScript.contains("find \"${'$'}directory\""))
        assertTrue(refresherScript.contains("home_root=${'$'}{DORO_HOME_ROOT:-/home}"))
        assertTrue(refresherScript.contains("for user_home in \"${'$'}home_root\"/*"))
        val aptHook = DesktopDefaults.systemProfileAssets.single {
            it.destination == "etc/apt/apt.conf.d/99linuxhub-launcher-refresh"
        }
        assertTrue(aptHook.asset.endsWith("apt-launcher-refresh.conf"))
        assertTrue(asset(aptHook.asset).contains("DPkg::Post-Invoke"))
        assertTrue(DesktopDefaults.applicationAssets.any {
            it.destination == "linuxhub-deb-installer.desktop"
        })
        assertTrue(DesktopDefaults.executableAssets.single {
            it.destination == "linuxhub-deb-installer"
        }.executable)
    }

    @Test fun refresherKeepsChromeActionArguments() {
        val root = Files.createTempDirectory("doro-refresher").toFile()
        try {
            val apps = File(root, "apps").apply { mkdirs() }
            val bin = File(root, "bin").apply { mkdirs() }
            File(bin, "id").apply {
                writeText("#!/bin/sh\nif [ \"${'$'}{1:-}\" = \"-u\" ]; then echo 0; exit 0; fi\nexec /usr/bin/id \"${'$'}@\"\n")
                setExecutable(true)
            }
            File(apps, "google-chrome.desktop").writeText(
                """
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
                """.trimIndent() + "\n",
            )
            val script = File("src/main/assets/${DesktopDefaults.ASSET_ROOT}/doro-refresh-launchers.sh")
                .absoluteFile
            val process = ProcessBuilder("/bin/sh", script.path).apply {
                environment()["PATH"] = "${bin.path}:/usr/bin:/bin:/usr/sbin:/sbin"
                environment()["DORO_APPLICATIONS_DIR"] = apps.path
                environment()["DORO_HOME_ROOT"] = File(root, "home").path
                redirectErrorStream(true)
            }.start()
            val output = process.inputStream.bufferedReader().readText()
            assertEquals(output, 0, process.waitFor())
            val updated = File(apps, "google-chrome.desktop").readText()
            assertTrue(updated.contains("Exec=/usr/local/bin/doro-chrome %U"))
            assertTrue(updated.contains("[Desktop Action new-window]\nName=New Window\nExec=/usr/local/bin/doro-chrome\n"))
            assertTrue(updated.contains("Exec=/usr/local/bin/doro-chrome --incognito"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun gpuApplicationLauncherIsAnExecutableDesktopAsset() {
        val launcher = DesktopDefaults.executableAssets.single { it.destination == "doro-gpu-run" }
        assertTrue(launcher.executable)
        val script = asset(launcher.asset)
        assertTrue(script.contains("MESA_LOADER_DRIVER_OVERRIDE=zink"))
        assertTrue(script.contains("MESA_LOADER_DRIVER_OVERRIDE=kgsl"))
        assertTrue(script.contains("LIBGL_ALWAYS_SOFTWARE=1"))
    }

    @Test fun userLabelIsAnExecutableDesktopAsset() {
        val label = DesktopDefaults.executableAssets.single { it.destination == "doro-user-label" }
        assertTrue(label.executable)
        val script = asset(label.asset)
        assertTrue(script.contains("<icon>avatar-default</icon>"))
        assertTrue(script.contains("<txt>%s</txt>"))
        val battery = DesktopDefaults.executableAssets.single { it.destination == "doro-battery-status" }
        assertTrue(battery.executable)
        assertTrue(asset(battery.asset).contains("battery-capacity"))
    }

    @Test fun sessionDoesNotRegenerateDesktopProfileOrOverrideUserTheme() {
        val script = DesktopCompatibility.sessionScript(":0", "doro", GpuRenderMode.KGSL)
        assertFalse(script.contains("doro-apply-desktop-defaults"))
        assertFalse(script.contains("doro-apply-wallpaper"))
        assertFalse(script.contains("xfdesktop --reload"))
        assertFalse(script.contains("GTK_THEME="))
        assertFalse(script.contains("GTK_ICON_THEME="))
    }

    @Test fun genmonDefaultsUsePerPluginRcFiles() {
        assertTrue(asset("${DesktopDefaults.ASSET_ROOT}/genmon-user-7.rc")
            .contains("Command=/usr/local/bin/doro-user-label"))
        assertTrue(asset("${DesktopDefaults.ASSET_ROOT}/genmon-battery-8.rc")
            .contains("Command=/usr/local/bin/doro-battery-status"))
    }
}
