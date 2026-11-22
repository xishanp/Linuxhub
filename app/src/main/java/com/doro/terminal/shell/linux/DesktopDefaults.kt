package com.doro.terminal.shell.linux

/**
 * Immutable desktop profile shipped in the APK.
 *
 * User configuration is installed only when the target file does not exist.
 * LinuxHub-owned CSS, launchers, themes and scripts are synchronized from the
 * APK. Personal GTK overrides live in gtk-user.css and are never overwritten.
 */
internal object DesktopDefaults {
    const val ASSET_ROOT = "desktop/profile"

    data class ProfileAsset(
        val asset: String,
        val destination: String,
        val executable: Boolean = false,
    )

    val homeProfileAssets = listOf(
        ProfileAsset(
            "$ASSET_ROOT/xfce4-panel.xml",
            ".config/xfce4/xfconf/xfce-perchannel-xml/xfce4-panel.xml",
        ),
        ProfileAsset(
            "$ASSET_ROOT/xfce4-desktop.xml",
            ".config/xfce4/xfconf/xfce-perchannel-xml/xfce4-desktop.xml",
        ),
        ProfileAsset(
            "$ASSET_ROOT/xsettings.xml",
            ".config/xfce4/xfconf/xfce-perchannel-xml/xsettings.xml",
        ),
        ProfileAsset(
            "$ASSET_ROOT/xfce4-terminal.xml",
            ".config/xfce4/xfconf/xfce-perchannel-xml/xfce4-terminal.xml",
        ),
        ProfileAsset(
            "$ASSET_ROOT/xfwm4.xml",
            ".config/xfce4/xfconf/xfce-perchannel-xml/xfwm4.xml",
        ),
        ProfileAsset("$ASSET_ROOT/gtk-settings.ini", ".config/gtk-3.0/settings.ini"),
        ProfileAsset("$ASSET_ROOT/gtk-user.css", ".config/gtk-3.0/gtk-user.css"),
        ProfileAsset("$ASSET_ROOT/gtk-settings.ini", ".config/gtk-4.0/settings.ini"),
        ProfileAsset("$ASSET_ROOT/gtk-user.css", ".config/gtk-4.0/gtk-user.css"),
        ProfileAsset("$ASSET_ROOT/genmon-user-7.rc", ".config/xfce4/panel/genmon-7.rc"),
        ProfileAsset("$ASSET_ROOT/genmon-battery-8.rc", ".config/xfce4/panel/genmon-8.rc"),
        ProfileAsset("$ASSET_ROOT/plank-autostart.desktop", ".config/autostart/plank.desktop"),
        ProfileAsset("$ASSET_ROOT/mimeapps.list", ".config/mimeapps.list"),
        ProfileAsset("$ASSET_ROOT/helpers.rc", ".config/xfce4/helpers.rc"),
    )

    val managedHomeProfileAssets = listOf(
        ProfileAsset("$ASSET_ROOT/gtk.css", ".config/gtk-3.0/gtk.css"),
        ProfileAsset("$ASSET_ROOT/linuxhub-material.css", ".config/gtk-3.0/linuxhub-material.css"),
        ProfileAsset("$ASSET_ROOT/gtk.css", ".config/gtk-4.0/gtk.css"),
        ProfileAsset("$ASSET_ROOT/linuxhub-material.css", ".config/gtk-4.0/linuxhub-material.css"),
    )

    val applicationAssets = listOf(
        ProfileAsset("$ASSET_ROOT/doro-terminal.desktop", "doro-terminal.desktop"),
        ProfileAsset("$ASSET_ROOT/doro-home.desktop", "doro-home.desktop"),
        ProfileAsset("$ASSET_ROOT/google-chrome.desktop", "google-chrome.desktop"),
        ProfileAsset(
            "$ASSET_ROOT/linuxhub-deb-installer.desktop",
            "linuxhub-deb-installer.desktop",
        ),
    )

    val systemProfileAssets = listOf(
        ProfileAsset(
            "$ASSET_ROOT/plank-dock.theme",
            "usr/share/plank/themes/LinuxHub-Dock/dock.theme",
        ),
        ProfileAsset(
            "$ASSET_ROOT/apt-launcher-refresh.conf",
            "etc/apt/apt.conf.d/99linuxhub-launcher-refresh",
        ),
    )

    val executableAssets = listOf(
        ProfileAsset("$ASSET_ROOT/doro-gpu-run.sh", "doro-gpu-run", executable = true),
        ProfileAsset("$ASSET_ROOT/doro-start-plank.sh", "doro-start-plank", executable = true),
        ProfileAsset("$ASSET_ROOT/doro-user-label.sh", "doro-user-label", executable = true),
        ProfileAsset("$ASSET_ROOT/doro-battery-status.sh", "doro-battery-status", executable = true),
        ProfileAsset(
            "$ASSET_ROOT/doro-wallpaper-guard.sh",
            "doro-wallpaper-guard",
            executable = true,
        ),
        ProfileAsset(
            "$ASSET_ROOT/doro-x11-screenshot.py",
            "doro-x11-screenshot",
            executable = true,
        ),
        ProfileAsset(
            "$ASSET_ROOT/doro-x11-control.py",
            "doro-x11-control",
            executable = true,
        ),
        ProfileAsset(
            "$ASSET_ROOT/doro-x11-input.py",
            "doro-x11-input",
            executable = true,
        ),
        ProfileAsset(
            "$ASSET_ROOT/linuxhub-deb-installer.sh",
            "linuxhub-deb-installer",
            executable = true,
        ),
        ProfileAsset(
            "$ASSET_ROOT/doro-refresh-launchers.sh",
            "doro-refresh-launchers",
            executable = true,
        ),
    )
}
