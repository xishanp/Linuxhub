package com.doro.terminal.shell.linux

internal data class OfflineInstallAction(
    val packageName: String,
    val phase: Phase,
) {
    enum class Phase { INSTALLING, CONFIGURING, REFRESHING }

    /** dpkg reports the architecture while configuring, but unpacking omits it. */
    val packageKey: String
        get() = packageName.substringBefore(':')

    val countsAsProcessedPackage: Boolean
        get() = phase == Phase.INSTALLING || phase == Phase.CONFIGURING
}

/** Converts dpkg output into a concise status line suitable for the setup screen. */
internal object OfflineInstallProgress {
    private val packageAction = Regex(
        """^\s*(Unpacking|Setting up|Processing triggers for)\s+([^\s(]+).*""",
    )

    fun parse(line: String): OfflineInstallAction? {
        val match = packageAction.find(line) ?: return null
        val action = match.groupValues[1]
        val packageName = match.groupValues[2]
        val phase = when (action) {
            "Setting up" -> OfflineInstallAction.Phase.CONFIGURING
            "Unpacking" -> OfflineInstallAction.Phase.INSTALLING
            else -> OfflineInstallAction.Phase.REFRESHING
        }
        return OfflineInstallAction(packageName, phase)
    }

    fun message(
        action: OfflineInstallAction?,
        totalPackages: Int,
        processedPackages: Int,
    ): String {
        val prefix = when (action?.phase) {
            OfflineInstallAction.Phase.CONFIGURING -> "正在配置"
            OfflineInstallAction.Phase.INSTALLING -> "正在安装"
            OfflineInstallAction.Phase.REFRESHING -> "正在刷新"
            null -> "正在配置桌面和系统组件"
        }
        val packageName = action?.packageName?.let { " $it" }.orEmpty()
        val counter = if (totalPackages > 0) "（$processedPackages/$totalPackages）" else ""
        return "$prefix$packageName$counter"
    }
}
