package com.doro.terminal.shell.linux

object VendorAppsCheckScript {
    fun check(): String =
        "command -v firefox >/dev/null || { echo '缺少 firefox 命令'; exit 1; }; " +
            "test -f /usr/share/applications/firefox.desktop || { echo '缺少 Firefox 桌面入口'; exit 1; }; " +
            "doro_missing=$(LD_LIBRARY_PATH=/opt/firefox ldd /opt/firefox/firefox-bin /opt/firefox/libxul.so 2>&1 | grep -E 'not found|version .* not found' || true); " +
            "test -z \"${'$'}doro_missing\" || { echo \"Firefox 缺少运行库:\n${'$'}doro_missing\"; exit 1; }"
}