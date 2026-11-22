package com.doro.terminal.shell.linux

/**
 * Repairs the parts of the vendor application overlay that dpkg normally
 * performs in maintainer scripts. The vendor archive is intentionally
 * extracted without registering hundreds of packages, so icon resources and
 * LibreOffice's URE registry must be materialized explicitly.
 *
 * The Chrome executable wrapper is owned by LinuxContainerManager because it
 * also has to opt the process into the selected GPU renderer. This script only
 * installs the static desktop resources.
 */
object VendorRuntimeRepair {
    fun script(): String = buildString {
        append("mkdir -p /etc/libreoffice/registry /etc/ld.so.conf.d; ")
        append("for size in 16 24 32 48 64 128 256; do ")
        append("src=/opt/google/chrome/product_logo_\${size}.png; ")
        append("[ -f \"\$src\" ] || continue; ")
        append("dest=/usr/share/icons/hicolor/\${size}x\${size}/apps; ")
        append("mkdir -p \"\$dest\"; ")
        append("cp -f \"\$src\" \"\$dest/google-chrome.png\"; ")
        append("done; ")
        append("if [ -f /usr/share/applications/google-chrome.desktop ]; then ")
        append("sed -i 's#^Exec=/usr/bin/google-chrome-stable#Exec=/usr/local/bin/doro-chrome#' ")
        append("/usr/share/applications/google-chrome.desktop; fi; ")
        append("if [ -d /usr/lib/libreoffice/share/.registry ]; then ")
        append("cp -af /usr/lib/libreoffice/share/.registry/. /etc/libreoffice/registry/; fi; ")
        append("printf '%s\\n' /usr/lib/libreoffice/program > /etc/ld.so.conf.d/libreoffice.conf; ")
        append("command -v ldconfig >/dev/null 2>&1 && ldconfig || :; ")
        append("command -v update-desktop-database >/dev/null 2>&1 && ")
        append("update-desktop-database -q /usr/share/applications || :; ")
        append("command -v gtk-update-icon-cache >/dev/null 2>&1 && ")
        append("gtk-update-icon-cache -q -t -f /usr/share/icons/hicolor || :")
    }
}
