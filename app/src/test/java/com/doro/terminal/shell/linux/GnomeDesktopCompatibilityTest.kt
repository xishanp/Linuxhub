package com.doro.terminal.shell.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GnomeDesktopCompatibilityTest {
    @Test
    fun usesOfficialUbuntuGnome46X11Compositor() {
        val script = GnomeDesktopCompatibility.sessionScript(":0", "doro", GpuRenderMode.SOFTWARE)
        assertTrue(script.contains("gnome-shell --x11 --replace --mode=linuxhub-ubuntu-min"))
        assertFalse(script.contains("/usr/bin/dbus-run-session"))
        assertTrue(script.contains("XDG_SESSION_TYPE=x11"))
        assertTrue(script.contains("XDG_CURRENT_DESKTOP=ubuntu:GNOME"))
        assertTrue(script.contains("G_RESOURCE_OVERLAYS='/org/gnome/shell=/usr/local/share/linuxhub/gnome-shell-overlay/org/gnome/shell'"))
        assertTrue(script.contains("GDK_BACKEND=x11 CLUTTER_BACKEND=x11"))
        assertFalse(script.contains("--devkit"))
        assertFalse(script.contains("--wayland"))
    }

    @Test
    fun usesThePRootSafePrivateSystemBusContract() {
        val services = GnomeDesktopCompatibility.systemServicesScript()
        val session = GnomeDesktopCompatibility.sessionScript(":0", "doro", GpuRenderMode.SOFTWARE)
        assertTrue(services.contains("DBUS_SYSTEM_BUS_ADDRESS='unix:path=/run/dbus/system_bus_socket'"))
        assertTrue(services.contains("/usr/local/share/linuxhub/linuxhub-system-bus.conf"))
        assertTrue(services.contains("system_bus=config_missing"))
        assertFalse(services.contains("dbus-daemon-launch-helper"))
        assertFalse(services.contains("s#<user>messagebus</user>##"))
        assertTrue(services.contains("system_bus=unreachable"))
        assertTrue(services.contains("--address=\"\$DBUS_SYSTEM_BUS_ADDRESS\""))
        assertTrue(services.contains("system_bus=reuse"))
        assertTrue(services.contains("/run/dbus/linuxhub-system-bus.pid"))
        assertFalse(services.contains("pkill -9 -x dbus-daemon"))
        assertTrue(session.contains("DBUS_SYSTEM_BUS_ADDRESS='unix:path=/run/dbus/system_bus_socket'"))
        assertTrue(session.contains("DBUS_SESSION_BUS_ADDRESS='unix:abstract=linuxhub-session-bus-doro'"))
        assertTrue(session.contains("doro_session_bus_abstract='linuxhub-session-bus-doro'"))
        assertTrue(session.contains("s|@SESSION_BUS_ABSTRACT@|\$doro_session_bus_abstract|g"))
        assertTrue(session.contains("linuxhub-session-bus.conf.in"))
        assertTrue(session.contains("dbus-send --bus=\"\$DBUS_SESSION_BUS_ADDRESS\""))
        assertTrue(session.contains("exec /usr/bin/gnome-shell --x11 --replace --mode=linuxhub-ubuntu-min"))
        assertTrue(session.contains("linuxhub-session-start.lock"))
        assertTrue(session.contains("session=reuse"))
        assertTrue(session.contains("session_bus=reuse"))
        assertFalse(session.contains("pkill -9 -x dbus-daemon"))
    }

    @Test
    fun doesNotAdvertiseLogindWithoutADbusOwner() {
        val services = GnomeDesktopCompatibility.systemServicesScript()
        assertTrue(services.contains("org.freedesktop.DBus.NameHasOwner string:org.freedesktop.login1"))
        assertTrue(services.contains("grep -q 'boolean true'"))
        assertTrue(services.contains("rmdir /run/systemd/seats"))
        assertTrue(services.contains("action=refuse_to_fake_logind"))
        assertTrue(services.contains("logind_features=disabled"))
    }

    @Test
    fun runsGnomeShellInTheForeground() {
        val script = GnomeDesktopCompatibility.sessionScript(":0", "doro", GpuRenderMode.SOFTWARE)
        assertTrue(script.contains("exec /usr/bin/gnome-shell --x11 --replace --mode=linuxhub-ubuntu-min"))
        assertFalse(script.contains("doro_shell_pid"))
        assertFalse(script.contains("gnome-shell --x11 --replace &"))
    }

    @Test
    fun generatedLaunchScriptsAreValidPosixShell() {
        assertShellSyntax(GnomeDesktopCompatibility.systemServicesScript())
        assertShellSyntax(GnomeDesktopCompatibility.sessionScript(":0", "doro", GpuRenderMode.SOFTWARE))
    }

    @Test
    fun runsDirectlyInAPrivateSessionBus() {
        val script = GnomeDesktopCompatibility.sessionScript(":0", "doro", GpuRenderMode.SOFTWARE)
        assertTrue(script.contains("exec /usr/bin/gnome-shell --x11 --replace --mode=linuxhub-ubuntu-min"))
        assertTrue(script.contains("dbus-daemon"))
        assertTrue(script.contains("--config-file"))
        assertTrue(script.contains("session_bus=ready"))
        assertTrue(script.contains("XDG_RUNTIME_DIR='/tmp/runtime-doro'"))
        assertTrue(script.contains("doro_outer_display=':0'"))
        assertFalse(script.contains("gnome-session --session"))
    }

    @Test
    fun desktopSessionLeavesOptionalHelpersOutsideTheShellLaunchPath() {
        val script = GnomeDesktopCompatibility.sessionScript(":0", "doro", GpuRenderMode.SOFTWARE)
        assertFalse(script.contains("launch.fifo"))
        assertFalse(script.contains("clipboard-daemon"))
        assertFalse(script.contains("dbus-update-activation-environment"))
    }

    @Test
    fun startsAudioAtLaunch() {
        val script = GnomeDesktopCompatibility.sessionScript(":0", "doro", GpuRenderMode.SOFTWARE)
        assertTrue(script.contains("export PULSE_SERVER=\"unix:\$XDG_RUNTIME_DIR/pulse/native\""))
        assertTrue(script.contains("pulseaudio.pa"))
        assertTrue(script.contains("doro-pulseaudio.log"))
    }

    @Test
    fun startsNativeInputAndDesktopHelpersThatCanSurviveProot() {
        val script = GnomeDesktopCompatibility.sessionScript(":0", "doro", GpuRenderMode.SOFTWARE)
        assertTrue(script.contains("fcitx5 -d --disable dbus >/tmp/doro-fcitx5.log"))
        assertTrue(script.contains("GTK_IM_MODULE=xim QT_IM_MODULE=xim"))
        assertTrue(script.contains("/usr/libexec/gsd-media-keys >/tmp/doro-gsd-media-keys.log"))
        assertTrue(script.contains("/usr/libexec/gsd-color >/tmp/doro-gsd-color.log"))
        assertTrue(script.contains("/usr/libexec/gsd-xsettings >/tmp/doro-gsd-xsettings.log"))
        assertTrue(script.contains("string:org.gnome.Shell"))
        assertTrue(script.contains("-x gsd-color"))
        assertTrue(script.contains("-x gsd-xsettings"))
        assertTrue(script.contains("-x fcitx5"))
        assertFalse(script.contains("/usr/libexec/gsd-power"))
        assertFalse(script.contains("/usr/libexec/gsd-rfkill"))
    }

    @Test
    fun desktopSessionUsesSoftwareRenderingInitially() {
        val script = GnomeDesktopCompatibility.sessionScript(":0", "doro", GpuRenderMode.DEFAULT)
        assertTrue(script.contains("doro_gpu_mode=software"))
        assertTrue(script.contains("MESA_LOADER_DRIVER_OVERRIDE=swrast"))
        assertTrue(script.contains("GALLIUM_DRIVER=llvmpipe LIBGL_ALWAYS_SOFTWARE=1"))
        assertFalse(script.contains("MESA_LOADER_DRIVER_OVERRIDE=kgsl"))
    }

    @Test
    fun desktopSessionDisablesSpiderMonkeyJitUnderProot() {
        val script = GnomeDesktopCompatibility.sessionScript(":0", "doro", GpuRenderMode.ZINK)
        assertTrue(script.contains("GJS_DISABLE_JIT=1"))
    }

    @Test
    fun hardwareCapabilityDoesNotOverrideMutterComposition() {
        listOf(
            GpuRenderMode.KGSL to "kgsl",
            GpuRenderMode.ZINK to "zink",
        ).forEach { (mode, capability) ->
            val script = GnomeDesktopCompatibility.sessionScript(":0", "doro", mode)
            assertTrue(script.contains("doro_gpu_mode=software"))
            assertTrue(script.contains("doro_gpu_capability=$capability"))
            assertTrue(script.contains("MESA_LOADER_DRIVER_OVERRIDE=swrast"))
            assertTrue(script.contains("GALLIUM_DRIVER=llvmpipe LIBGL_ALWAYS_SOFTWARE=1"))
            assertFalse(script.contains("MESA_LOADER_DRIVER_OVERRIDE=$capability"))
            assertFalse(script.contains("VK_ICD_FILENAMES=/usr/share/vulkan"))
            assertTrue(script.contains("gnome-shell --x11 --replace --mode=linuxhub-ubuntu-min"))
        }
    }

    @Test
    fun ubuntuSessionKeepsNativeQuickSettings() {
        val sessionMode = File("src/main/assets/desktop/gnome/overlay/org/gnome/shell/ui/sessionMode.js")
            .readText()
        assertTrue(
            sessionMode.contains(
                "right: ['screenRecording', 'screenSharing', 'dwellClick', 'a11y', 'keyboard', 'quickSettings']",
            ),
        )
    }

    @Test
    fun dingIgnoresUnreadableProotProcEntries() {
        val extension = File("src/main/assets/desktop/gnome/overlay/ding/extension.js").readText()
        assertTrue(extension.contains("One denied entry must not abort DING"))
        assertTrue(extension.contains("processUser.load_bytes(null)"))
        assertFalse(extension.contains("let [binaryData, etag] = processUser.load_bytes(null);"))
    }

    @Test
    fun offlineBundleShipsTheYaruIconTheme() {
        val packages = File("src/main/assets/offline/gnome-packages.txt").readLines()
        assertTrue(packages.any { it.startsWith("yaru-theme-icon_") })
    }

    @Test
    fun offlineBundleShipsSharedLibatomicRuntime() {
        val packageFile = File(
            "src/main/assets/offline/common/" +
                "libatomic1_14.2.0-4ubuntu2~24.04.1_arm64.deb",
        )
        assertTrue(packageFile.isFile)
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(packageFile.readBytes())
            .joinToString("") { "%02x".format(it) }
        assertEquals(
            "fdeab74e7ad8572cf69c7024e8040c7ac851f6e667d35f0d429e7a0352840f73",
            digest,
        )
    }

    @Test
    fun containsNoLegacyDesktopOrReplacementCompositor() {
        val script = GnomeDesktopCompatibility.sessionScript(":0", "doro", GpuRenderMode.SOFTWARE)
        assertFalse(script.contains("startxfce4"))
        assertFalse(script.contains("xfce4-panel"))
        assertFalse(script.contains("plank"))
        assertFalse(script.contains("weston"))
    }

    private fun assertShellSyntax(script: String) {
        val process = ProcessBuilder("sh", "-n").redirectErrorStream(true).start()
        process.outputStream.bufferedWriter().use { it.write(script) }
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()
        assertEquals(output, 0, exitCode)
    }
}
