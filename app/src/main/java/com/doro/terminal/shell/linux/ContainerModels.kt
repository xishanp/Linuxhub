package com.doro.terminal.shell.linux

data class TerminalLaunchSpec(
    val shellPath: String,
    val workingDirectory: String,
    val arguments: Array<String>,
    val environment: Array<String>
)

data class CommandExecutionResult(
    val exitCode: Int,
    val output: String,
    val timedOut: Boolean,
    val truncated: Boolean,
    val durationMs: Long
)

/**
 * A profile is the user's selected vendor route. AUTO is resolved once during
 * initialization; the resolved profile is persisted so an APK update does not
 * silently change an already validated GPU path.
 */
enum class GpuProfile(val id: String, val label: String) {
    AUTO("auto", "自动检测"),
    QUALCOMM("qualcomm", "高通 Adreno"),
    MEDIATEK("mediatek", "天玑 MediaTek"),
    SOFTWARE("software", "软件兼容");

    companion object {
        fun fromId(value: String?): GpuProfile =
            entries.firstOrNull { it.id == value?.trim()?.lowercase() } ?: AUTO
    }
}

data class ContainerConfig(
    val distribution: String = "ubuntu",
    val version: String = "24.04",
    val username: String,
    val gpuProfile: GpuProfile = GpuProfile.AUTO,
    val initialized: Boolean = true
)

/**
 * One monotonic percentage range for the whole initialization flow. Container
 * work owns 35-82, then account/system setup fills the remaining range.
 */
internal object InitializationProgressPlan {
    const val LOADER_CHECK = 36
    const val GNOME_ARCHIVE_START = 36
    const val GNOME_ARCHIVE_END = 42
    const val GNOME_PACKAGES_START = 42
    const val GNOME_PACKAGES_END = 68
    const val GNOME_COMPLETE = 69
    const val FASTFETCH = 70
    const val VENDOR_ARCHIVE_START = 71
    const val VENDOR_ARCHIVE_END = 76
    const val VENDOR_TASKS_START = 77
    const val VENDOR_TASKS_END = 82
    const val VENDOR_COMPLETE = 82
}

enum class InitializationStep(val progress: Int) {
    EXTRACT_SYSTEM(10),
    CREATE_CONTAINER(35),
    CREATE_USER(84),
    CONFIGURE_SUDO(88),
    INITIALIZE_ENVIRONMENT(92),
    SAVE_CONFIGURATION(98)
}

data class InitializationProgress(
    val step: InitializationStep,
    val message: String,
    val percent: Int = step.progress,
    val fraction: Float? = null,
    val tasks: List<InstallationTaskProgress> = emptyList(),
)

enum class InstallationTaskStatus {
    PENDING,
    RUNNING,
    SUCCESS,
    FAILED,
    TIMEOUT,
}

data class InstallationTaskProgress(
    val key: String,
    val label: String,
    val status: InstallationTaskStatus = InstallationTaskStatus.PENDING,
    val detail: String = "",
    val updatedAtMs: Long = 0L,
    val idleTimeoutSeconds: Int = 180,
)

data class InitializationState(
    val username: String,
    val components: Set<InstallComponent>,
    val gpuProfile: GpuProfile = GpuProfile.AUTO,
    val completedStep: InitializationStep? = null,
    val failedMessage: String? = null
)

enum class InstallComponent(
    val label: String,
    val category: String,
    val required: Boolean = false,
    val defaultSelected: Boolean = false,
    val supported: Boolean = true,
    val packages: List<String> = emptyList()
) {
    UBUNTU("Ubuntu 24.04", "基础组件", required = true, defaultSelected = true),
    SUDO("sudo", "基础组件", required = true, defaultSelected = true, packages = listOf("sudo")),
    APT("APT", "基础组件", required = true, defaultSelected = true),
    GIT("Git", "基础组件", packages = listOf("git")),
    PYTHON("Python", "基础组件", packages = listOf("python3", "python3-pip", "python3-venv")),
    CHINESE("中文语言环境", "基础组件", defaultSelected = true, packages = listOf("locales", "language-pack-zh-hans", "language-pack-gnome-zh-hans", "fonts-noto-cjk")),

    DESKTOP("GNOME 图形桌面", "桌面环境", defaultSelected = true),
    GNOME("GNOME 46 原生桌面", "内置组件", packages = listOf("gnome-shell", "mutter", "libmutter-14-0", "gnome-session", "gnome-settings-daemon", "gnome-control-center")),
    X11("X11", "内置组件", packages = listOf("dbus-x11", "x11-xserver-utils", "xterm")),
    PULSEAUDIO("PulseAudio", "内置组件", packages = listOf("pulseaudio")),
    FILE_MANAGER("文件管理器", "内置组件", packages = listOf("nautilus", "gnome-text-editor")),
    CHROME("Google Chrome", "内置应用"),

    OLLAMA("Ollama", "AI 开发", supported = false),
    LLAMA_CPP("llama.cpp", "AI 开发", supported = false),
    PYTHON_AI("Python AI 环境", "AI 开发", packages = listOf("python3-numpy", "python3-scipy", "python3-pandas", "python3-sklearn")),
    GPU("CUDA/Turnip（支持时）", "AI 开发", supported = false),
    JUPYTER("JupyterLab", "AI 开发", packages = listOf("jupyter-notebook")),

    WPS("WPS Office", "办公软件", supported = false),
    WECHAT("微信", "办公软件", supported = false),
    VSCODE("VS Code（code-server）", "办公软件", supported = false),

    NODE("Node.js", "开发环境", packages = listOf("nodejs", "npm")),
    JAVA("Java", "开发环境", packages = listOf("default-jdk")),
    GCC("GCC/G++", "开发环境", packages = listOf("build-essential")),
    CMAKE("CMake", "开发环境", packages = listOf("cmake")),
    RUST("Rust", "开发环境", packages = listOf("rustc", "cargo")),
    GO("Go", "开发环境", packages = listOf("golang-go"))
}
