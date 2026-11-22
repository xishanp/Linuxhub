package com.doro.terminal.shell.linux

object VendorAppsCheckScript {
    private val executables = listOf(
        "/opt/google/chrome/google-chrome",
        "/opt/google/chrome/chrome",
        "/usr/lib/thunderbird/thunderbird",
        "/usr/bin/thunderbird",
        "/usr/bin/libreoffice",
        "/usr/lib/libreoffice/program/soffice.bin",
        "/usr/bin/evince",
        "/usr/bin/rhythmbox",
        "/usr/bin/totem",
        "/usr/bin/shotwell",
        "/usr/bin/eog",
        "/usr/bin/snapshot",
        "/usr/bin/cheese",
        "/usr/bin/simple-scan",
        "/usr/bin/gnome-calculator",
        "/usr/bin/gnome-calendar",
        "/usr/bin/gnome-clocks",
        "/usr/bin/gnome-characters",
        "/usr/bin/gnome-contacts",
        "/usr/bin/gnome-font-viewer",
        "/usr/bin/gnome-logs",
        "/usr/bin/gnome-maps",
        "/usr/bin/gnome-weather",
        "/usr/bin/gnome-tweaks",
        "/usr/bin/gnome-disks",
        "/usr/bin/gnome-system-monitor",
        "/usr/bin/file-roller",
        "/usr/bin/baobab",
        "/usr/bin/seahorse",
        "/usr/bin/usb-creator-gtk",
        "/usr/bin/deja-dup",
        "/usr/bin/yelp",
        "/usr/bin/gnome-power-statistics",
        "/usr/bin/grdctl",
        "/usr/bin/orca",
        "/usr/bin/remmina",
        "/usr/bin/transmission-gtk",
    )

    private val desktopEntries = listOf(
        "google-chrome.desktop",
        "thunderbird.desktop",
        "libreoffice-startcenter.desktop",
        "libreoffice-writer.desktop",
        "libreoffice-calc.desktop",
        "libreoffice-impress.desktop",
        "org.gnome.Evince.desktop",
        "org.remmina.Remmina.desktop",
        "transmission-gtk.desktop",
        "org.gnome.Rhythmbox3.desktop",
        "org.gnome.Totem.desktop",
        "org.gnome.Shotwell.desktop",
        "org.gnome.eog.desktop",
        "org.gnome.Snapshot.desktop",
        "org.gnome.Cheese.desktop",
        "simple-scan.desktop",
        "org.gnome.Calculator.desktop",
        "org.gnome.Calendar.desktop",
        "org.gnome.clocks.desktop",
        "org.gnome.Characters.desktop",
        "org.gnome.Contacts.desktop",
        "org.gnome.font-viewer.desktop",
        "org.gnome.Logs.desktop",
        "org.gnome.Maps.desktop",
        "org.gnome.Weather.desktop",
        "org.gnome.tweaks.desktop",
        "org.gnome.DiskUtility.desktop",
        "org.gnome.SystemMonitor.desktop",
        "org.gnome.FileRoller.desktop",
        "org.gnome.baobab.desktop",
        "org.gnome.seahorse.Application.desktop",
        "usb-creator-gtk.desktop",
        "org.gnome.DejaDup.desktop",
        "yelp.desktop",
        "org.gnome.PowerStats.desktop",
        "org.gnome.RemoteDesktop.Handover.desktop",
    )

    fun check(includeSystemChecks: Boolean = true): String = buildString {
        append("set -e; ")
        append("fail() { printf '桌面应用检查失败：%s\n' \"${'$'}1\" >&2; exit 1; }; ")
        if (includeSystemChecks) {
            append("test -s /etc/ssl/certs/ca-certificates.crt || fail '缺少 CA 证书'; ")
            append("test -f /usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf || fail '缺少 Liberation 字体'; ")
            append("command -v openssl >/dev/null || fail '缺少 openssl'; ")
            append("command -v wget >/dev/null || fail '缺少 wget'; ")
        }
        executables.forEach { path ->
            append("test -x ${shell(path)} || fail '缺少可执行文件 ${path}'; ")
        }
        append("test -L /usr/local/bin/google-chrome || fail '缺少 Chrome 启动软链接'; ")
        append("test \"${'$'}(readlink /usr/local/bin/google-chrome)\" = /opt/google/chrome/google-chrome || fail 'Chrome 启动软链接错误'; ")
        append("test -s /usr/share/icons/hicolor/128x128/apps/google-chrome.png || fail '缺少 Chrome 桌面图标'; ")
        append("test -s /etc/libreoffice/registry/main.xcd || fail '缺少 LibreOffice 主注册表'; ")
        append("test -s /etc/libreoffice/registry/writer.xcd || fail '缺少 LibreOffice Writer 注册表'; ")
        desktopEntries.forEach { entry ->
            append("test -f ${shell("/usr/share/applications/$entry")} || fail '缺少桌面入口 $entry'; ")
        }
        append("test -f /usr/lib/thunderbird-addons/extensions/langpack-zh-CN@thunderbird.mozilla.org.xpi || fail '缺少 Thunderbird 简体中文语言包'; ")
        append("check_missing() { path=${'$'}1; env_name=${'$'}{2:-}; ")
        append("if command -v ldd >/dev/null 2>&1; then ")
        append("if [ \"${'$'}env_name\" = chrome ]; then ")
        append("doro_missing=${'$'}(LD_LIBRARY_PATH=/opt/google/chrome ldd \"${'$'}path\" 2>&1 | grep -E 'not found|version .* not found' || true); ")
        append("else doro_missing=${'$'}(ldd \"${'$'}path\" 2>&1 | grep -E 'not found|version .* not found' || true); fi; ")
        append("test -z \"${'$'}doro_missing\" || fail \"${'$'}path 缺少运行库: ${'$'}doro_missing\"; fi; }; ")
        executables.forEach { path ->
            val envName = if (path == "/opt/google/chrome/chrome") "chrome" else ""
            append("check_missing ${shell(path)} ${shell(envName)}; ")
        }
        if (includeSystemChecks) {
            append("doro_dpkg_audit=${'$'}(dpkg -C 2>&1); ")
            append("test -z \"${'$'}doro_dpkg_audit\" || fail \"dpkg 存在未完成状态: ${'$'}doro_dpkg_audit\"")
        }
    }

    private fun shell(value: String) = "'${value.replace("'", "'\\''")}'"
}
