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
            "install_batch() { " +
            "while IFS= read -r package; do " +
            "printf 'HUSK_DPKG_BEGIN %s\\n' \"\$package\"; " +
            "if ! dpkg \$dpkg_opts --unpack \"\$package\"; then " +
            "printf 'HUSK_DPKG_CONFIG_RETRY %s\\n' \"\$package\"; " +
            "dpkg \$dpkg_opts --configure -a || true; " +
            "dpkg \$dpkg_opts --unpack \"\$package\" || return \$?; fi; " +
            "done < \"\$1\"; }; " +
            "while IFS= read -r package || [ -n \"\$package\" ]; do " +
            "if [ -z \"\$package\" ]; then " +
            "if [ -s \"\$batch\" ]; then install_batch \"\$batch\" || exit \$?; : > \"\$batch\"; fi; " +
            "else printf '%s\\n' \"\$package\" >> \"\$batch\"; fi; " +
            "done < \"\$manifest\"; " +
            "if [ -s \"\$batch\" ]; then install_batch \"\$batch\" || exit \$?; fi; " +
            "dpkg \$dpkg_opts --configure -a || { dpkg -C || true; exit 1; }; " +
            "doro_dpkg_audit=\$(dpkg -C 2>&1); " +
            "if [ -n \"\$doro_dpkg_audit\" ]; then printf '%s\\n' \"\$doro_dpkg_audit\"; exit 1; fi"
}
