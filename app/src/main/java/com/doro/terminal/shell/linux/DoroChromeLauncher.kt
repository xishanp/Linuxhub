package com.doro.terminal.shell.linux

/**
 * Builds the product Chrome launcher. The renderer and window policy belong
 * here instead of being supplied by whatever shortcut or probe starts Chrome.
 */
internal object DoroChromeLauncher {
    private const val CHROME = "/opt/google/chrome/google-chrome"

    private val productArguments = listOf(
        "--no-sandbox",
        "--disable-dev-shm-usage",
        "--no-first-run",
        "--disable-breakpad",
    ).joinToString(" ")

    fun script(): String =
        "#!/bin/sh\n" +
            "export PULSE_SERVER=unix:\${XDG_RUNTIME_DIR}/pulse/native\n" +
            "export CHROME_WRAPPER=/usr/local/bin/doro-chrome\n" +
            "exec /usr/local/bin/doro-gpu-run $CHROME $productArguments \"\$@\"\n"
}
