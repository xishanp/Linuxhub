package com.doro.terminal.shell.linux

object SetupCatalog {
    val builtInComponents: Set<InstallComponent> = setOf(
        InstallComponent.SUDO,
        InstallComponent.CHINESE,
        InstallComponent.DESKTOP,
        InstallComponent.GNOME,
        InstallComponent.X11,
        InstallComponent.PULSEAUDIO,
        InstallComponent.FILE_MANAGER,
        InstallComponent.CHROME
    )

    private val onDemandComponents: Set<InstallComponent> = setOf(
        InstallComponent.GIT,
        InstallComponent.PYTHON,
        InstallComponent.NODE,
        InstallComponent.GCC,
        InstallComponent.CMAKE,
        InstallComponent.JAVA,
        InstallComponent.RUST,
        InstallComponent.GO,
        InstallComponent.PYTHON_AI,
        InstallComponent.JUPYTER
    )

    val hiddenComponents: Set<InstallComponent> = builtInComponents + onDemandComponents + setOf(
        InstallComponent.UBUNTU,
        InstallComponent.APT
    )

    val optionalComponents: List<InstallComponent> = InstallComponent.entries.filter { component ->
        component !in hiddenComponents && component != InstallComponent.WPS && component != InstallComponent.VSCODE && component.supported
    }

    fun resolveSelection(selected: Set<InstallComponent>): Set<InstallComponent> {
        val desktopBuiltIns = if (InstallComponent.DESKTOP in selected) {
            setOf(
                InstallComponent.GNOME,
                InstallComponent.X11,
                InstallComponent.PULSEAUDIO,
                InstallComponent.FILE_MANAGER
            )
        } else emptySet()
        return selected + builtInComponents + desktopBuiltIns + InstallComponent.entries.filter { it.required }
    }
}
