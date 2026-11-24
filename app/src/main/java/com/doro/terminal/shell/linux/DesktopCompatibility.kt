package com.doro.terminal.shell.linux

internal object DesktopCompatibility {
    const val DEFAULT_WALLPAPER = "/usr/share/backgrounds/xfce/doro-ubuntu.png"

    fun sessionScript(display: String, username: String, gpuMode: GpuRenderMode): String {
        return "export DISPLAY=${quote(display)}; " +
            "if locale -a 2>/dev/null | grep -qi '^zh_CN\\.utf8$'; then " +
            "export LANG=zh_CN.UTF-8 LC_ALL=zh_CN.UTF-8 LANGUAGE=zh_CN:zh; " +
            "else export LANG=C.UTF-8 LC_ALL=C.UTF-8; unset LANGUAGE; fi; " +
            "export XDG_RUNTIME_DIR=/tmp/runtime-${quote(username)}; " +
            "mkdir -p \"\$XDG_RUNTIME_DIR\"; chmod 700 \"\$XDG_RUNTIME_DIR\"; " +
            "export PATH=/usr/local/lib/doro-software-bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/usr/games; " +
            GpuRuntimeEnvironment.sessionShell(gpuMode) +
            "export XMODIFIERS=@im=fcitx GTK_IM_MODULE=fcitx QT_IM_MODULE=fcitx; " +
            "export MOZ_ENABLE_WAYLAND=0; " +
            "export NO_AT_BRIDGE=1; " +
            "unset DBUS_SESSION_BUS_ADDRESS DBUS_SESSION_BUS_PID SESSION_MANAGER; " +
            "pkill -9 -x xfce4-session 2>/dev/null || true; pkill -9 -x startxfce4 2>/dev/null || true; " +
            "pkill -9 -x xfce4-panel 2>/dev/null || true; pkill -9 -x xfdesktop 2>/dev/null || true; pkill -9 -x xfwm4 2>/dev/null || true; " +
            "pkill -9 -x xfce4-power-manager 2>/dev/null || true; pkill -9 -x upowerd 2>/dev/null || true; " +
            "pkill -9 -x xfconfd 2>/dev/null || true; sleep 2; " +
            "exec dbus-launch --exit-with-session /bin/sh -c '" +
            "command -v fcitx5 >/dev/null 2>&1 && fcitx5 -d; " +
            "if [ -x /usr/lib/policykit-1-gnome/polkit-gnome-authentication-agent-1 ]; then " +
            "/usr/lib/policykit-1-gnome/polkit-gnome-authentication-agent-1 >/tmp/doro-polkit-agent.log 2>&1 & fi; " +
            "/usr/local/bin/doro-x11-control --display ${quote(display)} clipboard-daemon " +
            "--socket ${quote("@linuxhub-clipboard-$username")} >/tmp/doro-clipboardd.log 2>&1 & " +
            "echo \$! > \"\$XDG_RUNTIME_DIR/linuxhub-clipboard.pid\"; " +
            "/usr/local/bin/doro-wallpaper-guard >/tmp/doro-wallpaper-guard.log 2>&1 & " +
            "exec startxfce4'"
    }

    fun repairBrokenTerminalMaterial(content: String): String =
        content.replace(
            "name=\"background-darkness\" type=\"double\" value=\"0.0\"",
            "name=\"background-darkness\" type=\"double\" value=\"0.72\"",
        )

    fun repairOpaqueWindowOpacity(content: String): String =
        content.replace(
            "<property name=\"frame_opacity\" type=\"int\" value=\"100\"/>",
            "<property name=\"frame_opacity\" type=\"int\" value=\"72\"/>",
        )

    fun repairDynamicWallpaper(content: String, target: String = DEFAULT_WALLPAPER): String {
        val lastImage = Regex(
            """(<property\s+name="last-image"\s+type="string"\s+value=")([^"]*)(")"""
        )
        val knownXfceDefaults = setOf(
            "xfce-shapes.svg",
            "xfce-stripes.png",
            "xfce-teal.jpg",
        )
        return lastImage.replace(content) { match ->
            val current = match.groupValues[2]
            val filename = current.substringAfterLast('/')
            if (current.isBlank() || filename in knownXfceDefaults) {
                match.groupValues[1] + target + match.groupValues[3]
            } else {
                match.value
            }
        }
    }

    fun repairBrokenPanelMaterial(content: String): String {
        val broken = "<property name=\"background-style\" type=\"uint\" value=\"0\"/>\n" +
            "      <property name=\"enter-opacity\" type=\"uint\" value=\"100\"/>"
        val transparentPanel = "<property name=\"background-style\" type=\"uint\" value=\"1\"/>\n" +
            "      <property name=\"background-rgba\" type=\"array\">\n" +
            "        <value type=\"double\" value=\"0.0\"/>\n" +
            "        <value type=\"double\" value=\"0.0\"/>\n" +
            "        <value type=\"double\" value=\"0.0\"/>\n" +
            "        <value type=\"double\" value=\"0.0\"/>\n" +
            "      </property>\n" +
            "      <property name=\"enter-opacity\" type=\"uint\" value=\"100\"/>"
        val repaired = content.replaceFirst(broken, transparentPanel)
        val legacyGraphiteRgba = Regex(
            """(?s)<property name="background-rgba" type="array">\s*""" +
                """<value type="double" value="0\.168627"/>\s*""" +
                """<value type="double" value="0\.180392"/>\s*""" +
                """<value type="double" value="0\.[0-9]+"/>\s*""" +
                """<value type="double" value="0\.[0-9]+"/>\s*</property>"""
        )
        val transparentRgba = "<property name=\"background-rgba\" type=\"array\">\n" +
            "        <value type=\"double\" value=\"0.0\"/>\n" +
            "        <value type=\"double\" value=\"0.0\"/>\n" +
            "        <value type=\"double\" value=\"0.0\"/>\n" +
            "        <value type=\"double\" value=\"0.0\"/>\n" +
            "      </property>"
        return repaired.replaceFirst(legacyGraphiteRgba, transparentRgba)
    }

    fun setDesktopKey(content: String, key: String, value: String): String {
        val property = Regex("""(?m)^${Regex.escape(key)}=.*$""")
        val replacement = "$key=$value"
        val updated = if (property.containsMatchIn(content)) {
            property.replace(content, replacement)
        } else {
            content.trimEnd() + "\n$replacement\n"
        }
        return if (updated.endsWith('\n')) updated else updated + "\n"
    }

    /**
     * Replaces a key only inside one Desktop Entry section. Chrome exposes
     * extra actions such as "new-window" and "new-private-window"; replacing
     * every Exec= line would erase --incognito and turn every action into a
     * normal launch.
     */
    private fun setDesktopSectionKey(
        content: String,
        sectionName: String,
        key: String,
        value: String,
    ): String {
        val lines = content.lines().toMutableList()
        var currentSection = ""
        var targetStart = -1
        var targetEnd = lines.size
        var replaced = false

        lines.forEachIndexed { index, line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                if (currentSection == sectionName && !replaced) targetEnd = index
                currentSection = trimmed.removePrefix("[").removeSuffix("]")
                if (currentSection == sectionName) targetStart = index
                return@forEachIndexed
            }
            if (currentSection == sectionName && line.substringBefore('=').trim() == key) {
                lines[index] = "$key=$value"
                replaced = true
            }
        }

        if (!replaced && targetStart >= 0) {
            lines.add(targetEnd.coerceAtMost(lines.size), "$key=$value")
        } else if (!replaced && sectionName == "Desktop Entry") {
            lines.addAll(0, listOf("[Desktop Entry]", "$key=$value", ""))
        }
        val updated = lines.joinToString("\n")
        return if (updated.endsWith('\n')) updated else updated + "\n"
    }

    fun setDesktopEntryExec(content: String, command: String): String =
        setDesktopSectionKey(content, "Desktop Entry", "Exec", command)

    fun setDesktopActionExec(content: String, action: String, command: String): String =
        setDesktopSectionKey(content, "Desktop Action $action", "Exec", command)

    fun patchChromeDesktop(content: String): String {
        var updated = setDesktopKey(content, "Icon", "google-chrome")
        updated = setDesktopEntryExec(updated, "/usr/local/bin/doro-chrome %U")
        updated = setDesktopActionExec(updated, "new-window", "/usr/local/bin/doro-chrome")
        updated = setDesktopActionExec(
            updated,
            "new-private-window",
            "/usr/local/bin/doro-chrome --incognito",
        )
        return updated
    }

    fun ensureDebMimeAssociations(content: String): String {
        val associations = linkedMapOf(
            "application/vnd.debian.binary-package" to "linuxhub-deb-installer.desktop;",
            "application/x-deb" to "linuxhub-deb-installer.desktop;",
            "application/x-debian-package" to "linuxhub-deb-installer.desktop;",
        )
        val lines = content.lines().toMutableList()
        var section = lines.indexOf("[Default Applications]")
        if (section < 0) {
            lines.addAll(0, listOf("[Default Applications]", ""))
            section = 0
        }
        associations.forEach { (mime, desktop) ->
            val sectionEnd = lines.withIndex()
                .drop(section + 1)
                .firstOrNull { it.value.startsWith("[") && it.value.endsWith("]") }
                ?.index ?: lines.size
            val existing = (section + 1 until sectionEnd).firstOrNull { index ->
                lines[index].substringBefore('=').trim() == mime
            }
            if (existing != null) {
                lines[existing] = "$mime=$desktop"
            } else {
                lines.add(section + 1, "$mime=$desktop")
            }
        }
        return lines.joinToString("\n").trimEnd() + "\n"
    }

    private val softwareOnlyDesktopIds = setOf(
        "xfce4-session.desktop",
        "xfwm4.desktop",
        "xfdesktop.desktop",
        "xfce4-panel.desktop",
        "panel-desktop-handler.desktop",
        "panel-preferences.desktop",
        "plank.desktop",
    )
    private val applicationWrapperPrefix = "/usr/local/bin/doro-gpu-run "
    private val preWrappedApplications = listOf(
        applicationWrapperPrefix,
        "/usr/local/bin/doro-chrome",
        "/usr/local/bin/doro-wechat",
    )

    /**
     * Every launchable application gets a private hardware renderer. Desktop
     * shell components stay on the session's software environment to avoid the
     * Lorie/KGSL black-screen failure mode.
     */
    fun wrapApplicationWithGpu(content: String, name: String): String {
        if (name in softwareOnlyDesktopIds) return content
        return content.lineSequence().joinToString("\n") { originalLine ->
            if (!originalLine.startsWith("Exec=")) return@joinToString originalLine
            val command = originalLine.removePrefix("Exec=")
            if (command.isBlank() || preWrappedApplications.any(command::startsWith)) {
                originalLine
            } else {
                "Exec=$applicationWrapperPrefix$command"
            }
        }
    }

    fun patchDesktop(content: String): String {
        val legacyGpuPrefix = Regex("""^(?:/usr/local/bin/doro-gpu-run\s+)+""")
        return content.lineSequence().joinToString("\n") { originalLine ->
            val trimmed = originalLine.trim()
            val repairedLine = if (
                trimmed.isNotEmpty() &&
                !trimmed.startsWith("#") &&
                !trimmed.startsWith("[") &&
                !trimmed.contains('=')
            ) "Exec=$trimmed" else originalLine
            if (!repairedLine.startsWith("Exec=")) return@joinToString repairedLine
            var command = repairedLine.removePrefix("Exec=")
                .replaceFirst(legacyGpuPrefix, "")
            "Exec=$command"
        }
    }

    private fun quote(value: String) = "'${value.replace("'", "'\\''")}'"
}
