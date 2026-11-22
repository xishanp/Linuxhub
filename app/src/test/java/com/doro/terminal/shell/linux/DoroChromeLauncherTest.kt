package com.doro.terminal.shell.linux

import org.junit.Assert.assertEquals
import org.junit.Test

class DoroChromeLauncherTest {
    @Test
    fun productLauncherUsesDoroGpuEnvironmentWithoutOverridingChromeBackend() {
        val script = DoroChromeLauncher.script()

        assertEquals(
            "#!/bin/sh\n" +
                "export PULSE_SERVER=unix:\${XDG_RUNTIME_DIR}/pulse/native\n" +
                "export CHROME_WRAPPER=/usr/local/bin/doro-chrome\n" +
                "exec /usr/local/bin/doro-gpu-run /opt/google/chrome/google-chrome " +
                "--no-sandbox --disable-dev-shm-usage --no-first-run --disable-breakpad " +
                "\"\$@\"\n",
            script,
        )

        val validation = ProcessBuilder("/bin/sh", "-n", "-c", script)
            .redirectErrorStream(true)
            .start()
        val diagnostics = validation.inputStream.bufferedReader().readText()
        assertEquals(diagnostics, 0, validation.waitFor())
    }
}
