package com.doro.terminal.shell.linux

object OfflineInstallScript {
    fun build(packageList: String): String =
        "set -e; " +
            "export DEBIAN_FRONTEND=noninteractive UCF_FORCE_CONFFOLD=1 NEEDRESTART_MODE=a; " +
            "dpkg_opts='--force-confdef --force-confold'; " +
            "manifest=/var/cache/doro-offline/$packageList; " +
            "test -s \"\$manifest\"; " +
            "cd /var/cache/doro-offline/packages; " +
            "batch=/tmp/doro-dpkg-batch; : > \"\$batch\"; " +
            "while IFS= read -r package || [ -n \"\$package\" ]; do " +
            "if [ -z \"\$package\" ]; then " +
            "if [ -s \"\$batch\" ]; then xargs -a \"\$batch\" dpkg \$dpkg_opts -i; : > \"\$batch\"; fi; " +
            "else printf '%s\\n' \"\$package\" >> \"\$batch\"; fi; " +
            "done < \"\$manifest\"; " +
            "if [ -s \"\$batch\" ]; then xargs -a \"\$batch\" dpkg \$dpkg_opts -i; fi; " +
            "dpkg \$dpkg_opts --configure -a; " +
            "doro_dpkg_audit=\$(dpkg -C 2>&1); " +
            "if [ -n \"\$doro_dpkg_audit\" ]; then printf '%s\\n' \"\$doro_dpkg_audit\"; exit 1; fi"
}
