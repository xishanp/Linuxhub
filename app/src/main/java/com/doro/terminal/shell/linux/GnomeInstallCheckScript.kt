package com.doro.terminal.shell.linux

internal object GnomeInstallCheckScript {
    fun check(): String =
        "set -e; " +
            "fail() { printf 'GNOME 组件检查失败：%s\\n' \"\$1\" >&2; exit 1; }; " +
            "for command in gnome-shell mutter gjs dbus-run-session xdg-open " +
            "gnome-control-center nautilus gnome-terminal gnome-text-editor glxinfo " +
            "xdg-user-dirs-update; do " +
            "command -v \"\$command\" >/dev/null || fail \"缺少命令 \$command\"; done; " +
            "test -x /usr/bin/gnome-shell || fail '缺少 /usr/bin/gnome-shell'; " +
            "for package in gnome-shell libmutter-14-0 mutter; do " +
            "dpkg-query -W -f='\${Status}' \"\$package\" | grep -q 'install ok installed' || " +
            "fail \"软件包 \$package 未正确安装\"; done; " +
            "for package in gnome-shell libmutter-14-0 mutter; do " +
            "dpkg-query -W -f='\${Version}' \"\$package\" | grep -q '^46\\.' || " +
            "fail \"软件包 \$package 版本不是 GNOME 46\"; done; " +
            "test -x /usr/sbin/NetworkManager || fail '缺少 NetworkManager'; " +
            "test -x /usr/libexec/bluetooth/bluetoothd || fail '缺少 BlueZ 服务'; " +
            "test -f /usr/libexec/at-spi-bus-launcher || fail '缺少 AT-SPI 服务'; " +
            "test -f /usr/lib/aarch64-linux-gnu/libmutter-14.so.0 || fail '缺少 Mutter 运行库'; " +
            "test -f /usr/lib/aarch64-linux-gnu/gdk-pixbuf-2.0/2.10.0/loaders/libpixbufloader-svg.so || fail '缺少 SVG 图标加载器'; " +
            "test -f /usr/lib/aarch64-linux-gnu/girepository-1.0/Dbusmenu-0.4.typelib || fail '缺少 Dbusmenu 类型库'; " +
            "test -d /usr/share/gnome-shell/extensions/ding@rastersoft.com || fail '缺少桌面图标扩展'; " +
            "test -d /usr/share/gnome-shell/extensions/ubuntu-dock@ubuntu.com || fail '缺少 Ubuntu Dock'; " +
            "for package in network-manager bluez at-spi2-core librsvg2-common xdg-user-dirs " +
            "gir1.2-dbusmenu-glib-0.4; do " +
            "dpkg-query -W -f='\${Status}' \"\$package\" | grep -q 'install ok installed' || " +
            "fail \"软件包 \$package 未正确安装\"; done; " +
            "gnome-shell --version | grep -q 'GNOME Shell 46\\.' || fail 'GNOME Shell 版本不匹配'; " +
            "mutter --version | grep -qi 'Mutter 46\\.' || fail 'Mutter 版本不匹配'; " +
            "doro_dpkg_audit=\$(dpkg -C 2>&1); " +
            "if [ -n \"\$doro_dpkg_audit\" ]; then printf '%s\\n' \"\$doro_dpkg_audit\"; fail 'dpkg 仍有未完成配置'; fi"
}
