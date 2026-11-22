package com.doro.terminal.shell.linux

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GnomeInstallCheckScriptTest {
    @Test
    fun verifiesOfficialUbuntuGnome46Packages() {
        val script = GnomeInstallCheckScript.check()
        assertTrue(script.contains("gnome-shell"))
        assertTrue(script.contains("libmutter-14-0"))
        assertTrue(script.contains("mutter"))
        assertTrue(script.contains("gjs"))
        assertTrue(script.contains("gnome-text-editor"))
        assertTrue(script.contains("xdg-user-dirs-update"))
        assertTrue(script.contains("/usr/sbin/NetworkManager"))
        assertTrue(script.contains("/usr/libexec/bluetooth/bluetoothd"))
        assertTrue(script.contains("/usr/libexec/at-spi-bus-launcher"))
        assertTrue(script.contains("libpixbufloader-svg.so"))
        assertTrue(script.contains("Dbusmenu-0.4.typelib"))
        assertTrue(script.contains("network-manager bluez at-spi2-core"))
        assertTrue(script.contains("gnome-shell --version"))
        assertTrue(script.contains("Mutter 46"))
        assertTrue(script.contains("GNOME 组件检查失败"))
        assertFalse(script.contains("mutter-dev-bin"))
        assertFalse(script.contains("libmutter-18-0"))
        assertFalse(script.contains("weston"))
    }
}
