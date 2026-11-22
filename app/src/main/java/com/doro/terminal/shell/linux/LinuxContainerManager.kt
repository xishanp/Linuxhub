package com.doro.terminal.shell.linux

import android.content.Context
import android.os.Build
import android.os.BatteryManager
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.security.MessageDigest
import java.util.Properties

class LinuxContainerManager(private val context: Context) {
    private val paths = ContainerPaths(context)
    private val audioBridge = ContainerAudioBridge(paths.audioPipe)
    private val virglServer = VirglServerController(context, paths)
    // Bridge process state is shared in the companion object below.


    fun isInitialized(): Boolean = loadConfig()?.initialized == true

    /**
     * Resolves a container-absolute path to the app-owned rootfs without allowing
     * path traversal outside it. Used by LinuxHub desktop helpers.
     */
    fun resolveContainerFile(path: String, mustExist: Boolean = true): File {
        require(path.startsWith('/')) { "容器路径必须是绝对路径" }
        require(path.none { it == '\u0000' || it == '\r' || it == '\n' }) { "容器路径包含非法字符" }
        val root = paths.rootfsDir.canonicalFile
        val candidate = File(root, path.removePrefix("/")).canonicalFile
        val rootPrefix = root.path + File.separator
        require(candidate.path == root.path || candidate.path.startsWith(rootPrefix)) { "容器路径越界" }
        if (mustExist) require(candidate.exists()) { "容器路径不存在: $path" }
        return candidate
    }

    fun loadConfig(): ContainerConfig? {
        if (!paths.configFile.isFile) return null
        return runCatching {
            val values = Properties().apply { paths.configFile.inputStream().use(::load) }
            ContainerConfig(
                distribution = values.getProperty("distribution", "ubuntu"),
                version = values.getProperty("version", "24.04"),
                username = values.getProperty("username"),
                gpuProfile = GpuProfile.fromId(values.getProperty("gpuProfile")),
                initialized = values.getProperty("initialized").toBoolean()
            )
        }.getOrNull()
    }

    fun initialize(
        username: String,
        password: CharArray,
        components: Set<InstallComponent>,
        gpuProfile: GpuProfile = GpuProfile.AUTO,
        onProgress: (InitializationProgress) -> Unit = {}
    ): ContainerConfig {
        require(LinuxAccount.isValidUsername(username)) { "用户名格式无效" }
        require(password.size >= 6) { "密码至少需要六位" }
        check(Build.SUPPORTED_64_BIT_ABIS.contains("arm64-v8a")) { "当前仅支持 ARM64 设备" }
        check(!isInitialized()) { "Ubuntu 已经初始化完成" }
        val requestedComponents = components.filter { it.supported }.toSet()
        var state = loadInitializationState()?.takeIf {
            it.username == username && it.gpuProfile == gpuProfile
        } ?: InitializationState(username, requestedComponents, gpuProfile)
        val selected = state.components
        saveInitializationState(state)
        prepareLog()

        try {
            fun execute(step: InitializationStep, message: String, action: () -> Unit) {
                if (state.completedStep != null && state.completedStep!!.ordinal >= step.ordinal) return
                onProgress(progress(step, message))
                appendLog("START ${step.name}: $message")
                action()
                state = state.copy(completedStep = step, failedMessage = null)
                saveInitializationState(state)
                appendLog("DONE ${step.name}")
            }

            execute(InitializationStep.EXTRACT_SYSTEM, "正在准备系统文件") {
                if (!paths.rootfsReadyMarker.isFile) {
                    paths.rootfsDir.deleteRecursively()
                    verifyAsset(ROOTFS_ASSET, ROOTFS_SHA256)
                    context.assets.open(ROOTFS_ASSET).use { TarGzExtractor.extract(it, paths.rootfsDir) }
                    File(paths.rootfsDir, "etc/dpkg/dpkg.cfg.d/PaxHeaders").deleteRecursively()
                    File(paths.rootfsDir, "etc/dpkg/dpkg.cfg.d/PaxHeaders.0").deleteRecursively()
                    val x11Share = File(paths.rootfsDir, "usr/share/X11")
                    verifyAsset(XKB_ASSET, XKB_SHA256)
                    context.assets.open(XKB_ASSET).use { TarGzExtractor.extract(it, x11Share) }
                    paths.rootfsReadyMarker.writeText("ubuntu-24.04\n")
                }
                installRuntime()
                if (gpuProfile != GpuProfile.SOFTWARE) {
                    installMesaGpuRuntime()
                }
            }
            execute(InitializationStep.CREATE_CONTAINER, "正在安装桌面和常用应用") {
                writeContainerFiles()
                ensureMergedUsrLayout(onProgress)
                installClientExecutables()
                installSystemProfileAssets()
                installGnomePackages(onProgress)
                ensureCommonRuntimePackages()
                installFastfetch(onProgress)
                // Every user-facing application is a final offline overlay. It
                // is unpacked only after the hardware/system layer is ready.
                installVendorApps(onProgress)
            }
            execute(InitializationStep.CREATE_USER, "正在创建登录账户") {
                runAsRoot(userCreationScript(username, password), "create-user")
            }
            execute(InitializationStep.CONFIGURE_SUDO, "正在设置管理员权限") {
                ensureSudoInstalled(username)
            }
            execute(InitializationStep.INITIALIZE_ENVIRONMENT, "正在完成系统设置") {
                runAsRoot(environmentScript(username), "initialize-environment")
                ensureUserDirectories(username)
                ensureDebPackageInstaller(username)
                // Fresh installs must contain the same GNOME overlay and Ubuntu
                // defaults as an already-initialized container. startDesktop()
                // still refreshes these APK-owned files before every launch.
                ensureGnomeSessionAssets(username)
            }
            var selectedProfile = gpuProfile
            execute(InitializationStep.SAVE_CONFIGURATION, "正在保存 GPU 线路") {
                if (gpuProfile != GpuProfile.SOFTWARE) installMesaGpuRuntime()
                val mode = prepareGpuRuntime(gpuProfile, enabled = true)
                selectedProfile = mode.toProfile()
                appendLog("GPU ROUTE requested=${gpuProfile.id} resolved=${mode.id}")
            }
            if (selectedProfile == GpuProfile.AUTO) {
                selectedProfile = DeviceGpuProbe.detect(paths.rootfsDir).toProfile()
            }
            return ContainerConfig(username = username, gpuProfile = selectedProfile).also {
                saveConfig(it)
                paths.initializationStateFile.delete()
                appendLog("INITIALIZATION COMPLETE")
            }
        } catch (failure: Throwable) {
            state = state.copy(failedMessage = failure.message)
            saveInitializationState(state)
            appendLog("FAILED: ${failure.stackTraceToString()}")
            throw IllegalStateException("${failure.message ?: "初始化失败"}\n日志：${paths.initializationLogFile.path}", failure)
        } finally {
            password.fill('\u0000')
        }
    }
    /**
     * Runs one-time package and profile repairs away from the Compose main
     * thread. Older containers may be missing sudo, so this also repairs the
     * package and root account before a desktop or terminal is used.
     */
    fun prepareContainerRuntime() {
        val config = loadConfig() ?: return
        if (!config.initialized) return
        // APK-owned installer assets are plain file copies and must not depend
        // on slower package maintenance succeeding first.
        ensureDebPackageInstaller(config.username)
        ensureStartupMaintenance(config.username)
        ensureSudoInstalled(config.username)
    }

    private fun ensureSudoInstalled(username: String) {
        val su = File(paths.rootfsDir, "usr/bin/su")
        val sudo = File(paths.rootfsDir, "usr/bin/sudo")
        val passwd = File(paths.rootfsDir, "usr/bin/passwd")
        val shadow = File(paths.rootfsDir, "etc/shadow")
        fun hasSetuid(file: File): Boolean = runCatching {
            file.isFile && (Os.stat(file.path).st_mode and OsConstants.S_ISUID) != 0
        }.getOrDefault(false)
        fun rootIsLocked(): Boolean = runCatching {
            val rootHash = shadow.readLines()
                .firstOrNull { it.startsWith("root:") }
                ?.split(':', limit = 3)
                ?.getOrNull(1)
                .orEmpty()
            rootHash.isEmpty() || rootHash.startsWith("*") || rootHash.startsWith("!")
        }.getOrDefault(true)
        if (
            hasSetuid(su) &&
            hasSetuid(sudo) &&
            hasSetuid(passwd) &&
            !rootIsLocked() &&
            paths.sudoReadyMarker.isFile
        ) return

        synchronized(sudoInstallLock) {
            if (!(sudo.isFile && sudo.canExecute())) {
                verifyAsset(SUDO_PACKAGE_ASSET, SUDO_PACKAGE_SHA256)
                val packageFile = File(paths.rootfsDir, "tmp/$SUDO_PACKAGE_FILE")
                packageFile.parentFile?.mkdirs()
                copyAsset(SUDO_PACKAGE_ASSET, packageFile)
                try {
                    runAsRoot(
                        "export DEBIAN_FRONTEND=noninteractive UCF_FORCE_CONFFOLD=1 NEEDRESTART_MODE=a; " +
                            "dpkg --force-confdef --force-confold -i /tmp/$SUDO_PACKAGE_FILE; " +
                            "dpkg --force-confdef --force-confold --configure -a; " +
                            "test -x /usr/bin/sudo",
                        "sudo-package",
                        timeoutMs = COMMAND_TIMEOUT_MS,
                        idleTimeoutMs = COMMAND_IDLE_TIMEOUT_MS,
                    )
                } finally {
                    packageFile.delete()
                }
            }

            runAsRoot(sudoAccessScript(username), "sudo-access")
            check(!rootIsLocked()) { "root 账户密码修复失败" }
            check(hasSetuid(su)) { "su 提权位修复失败" }
            check(hasSetuid(sudo)) { "sudo 提权位修复失败" }
            check(hasSetuid(passwd)) { "passwd 提权位修复失败" }
            paths.sudoReadyMarker.writeText("$SUDO_PACKAGE_SHA256\n$SUDO_ACCESS_MARKER_VERSION\n")
        }
    }

    private fun ensureStartupMaintenance(username: String) {
        val revision = installedProfileRevision()
        if (startupMaintenanceRevision == revision) return
        synchronized(startupMaintenanceLock) {
            if (startupMaintenanceRevision == revision) return
            ensureTrustedCertificates()
            ensureCommonRuntimePackages()
            ensureInstalledPackages()
            ensureFetchScript()
            installChineseTranslationPolicy()
            removeDeprecatedSparkStore()
            if (!File(paths.rootfsDir, "usr/bin/fastfetch").isFile) {
                val packageFile = File(paths.rootfsDir, "tmp/$FASTFETCH_FILE")
                packageFile.parentFile?.mkdirs()
                verifyAsset(FASTFETCH_ASSET, FASTFETCH_SHA256)
                copyAsset(FASTFETCH_ASSET, packageFile)
                runAsRoot(
                    "export DEBIAN_FRONTEND=noninteractive UCF_FORCE_CONFFOLD=1; dpkg --force-confdef --force-confold -i /tmp/$FASTFETCH_FILE && command -v fastfetch >/dev/null",
                    "startup-fastfetch",
                    timeoutMs = COMMAND_TIMEOUT_MS
                )
                packageFile.delete()
                paths.fastfetchReadyMarker.writeText("fastfetch-2.66.0\n")
            }
            ensureTerminalProfile(username)
            startupMaintenanceRevision = revision
        }
    }

    /**
     * Package assets are versioned by content, not by an install-step name. An APK update
     * can therefore replace a stale rootfs without silently keeping packages from an older
     * bundle. The two installers are idempotent and only touch the container when their
     * content marker is missing or different.
     */
    private fun ensureInstalledPackages() {
        if (!paths.rootfsReadyMarker.isFile) return
        synchronized(startupMaintenanceLock) {
            installGnomePackages {}
            ensureVendorAppsInstalled()
        }
    }

    /**
     * Android package updates preserve the container. A new APK can therefore
     * carry a newer vendor overlay while the old container still has the old
     * marker. A successful overlay installation already runs the full vendor
     * verification before writing this content-addressed marker; repeating that
     * check on every desktop launch only adds PRoot startup latency.
     */
    private fun ensureVendorAppsInstalled() {
        val installed = runCatching {
            paths.vendorAppsReadyMarker.readText().trim()
        }.getOrNull()
        if (VendorAppsUpgradePolicy.requiresInstall(installed, VENDOR_APPS_SHA256)) {
            installVendorApps {}
        } else {
            appendLog("VENDOR_APPS marker-ready")
        }
    }

    private fun ensureVendorRuntimeRepair() {
        if (!paths.rootfsReadyMarker.isFile) return
        val installedMarker = runCatching {
            paths.vendorRuntimeRepairMarker.readText().trim()
        }.getOrNull()
        if (!VendorAppsUpgradePolicy.requiresRuntimeRepair(
                installedMarker,
                VENDOR_APPS_SHA256,
            )
        ) return
        synchronized(vendorRuntimeRepairLock) {
            val current = runCatching {
                paths.vendorRuntimeRepairMarker.readText().trim()
            }.getOrNull()
            if (!VendorAppsUpgradePolicy.requiresRuntimeRepair(current, VENDOR_APPS_SHA256)) {
                return
            }
            runCatching {
                runAsRoot(
                    VendorRuntimeRepair.script(),
                    "vendor-runtime-repair",
                    timeoutMs = COMMAND_TIMEOUT_MS,
                    idleTimeoutMs = COMMAND_IDLE_TIMEOUT_MS,
                )
            }.onSuccess {
                paths.vendorRuntimeRepairMarker.writeText(
                    "${VendorAppsUpgradePolicy.runtimeRepairMarker(VENDOR_APPS_SHA256)}\n",
                )
            }.onFailure {
                appendLog("WARN vendor runtime repair failed: ${it.message}")
            }
        }
    }

    /** Synchronizes LinuxHub-owned helpers without touching GNOME user settings. */
    /** Installs only the desktop X11 helper without rewriting desktop user assets. */
    fun ensureX11ControlAsset() {
        if (!isInitialized()) return
        val destinations = setOf("doro-x11-control", "doro-x11-screenshot", "doro-x11-input")
        val profileAssets = DesktopDefaults.executableAssets.filter {
            it.destination in destinations
        }
        synchronized(desktopProfileLock) {
            profileAssets.forEach { profileAsset ->
                copyProfileAsset(
                    profileAsset,
                    File(paths.rootfsDir, "usr/local/bin/${profileAsset.destination}"),
                )
            }
        }
    }

    fun ensureDesktopRuntimeAssets(gpuAcceleration: Boolean = true) {
        val config = requireNotNull(loadConfig()) { "Ubuntu 尚未初始化" }
        val home = File(paths.rootfsDir, "home/${config.username}")
        if (!home.isDirectory) return
        synchronized(desktopProfileLock) {
            // The Mesa bundle is small and carries both the Qualcomm and the
            // MediaTek guest drivers. Install it once during initialization or
            // on the first launch of an older container; never delete software
            // drivers, because they are the safe fallback.
            if (gpuAcceleration && config.gpuProfile != GpuProfile.SOFTWARE) {
                installMesaGpuRuntime()
            }
            prepareGpuRuntime(config.gpuProfile, gpuAcceleration)
            DesktopDefaults.executableAssets.forEach { profileAsset ->
                copyProfileAsset(
                    profileAsset,
                    File(paths.rootfsDir, "usr/local/bin/${profileAsset.destination}"),
                )
            }
        }
    }

    private fun ensureDebPackageInstaller(username: String) {
        synchronized(installerAssetsLock) {
            ensureDebPackageInstallerLocked(username)
        }
    }

    private fun ensureDebPackageInstallerLocked(username: String) {
        val home = File(paths.rootfsDir, "home/$username")
        if (!home.isDirectory) return
        val markerVersion = "$DEB_INSTALLER_ASSETS_VERSION:${installedProfileRevision()}"
        if (paths.desktopInstallerAssetsMarker.isFile &&
            paths.desktopInstallerAssetsMarker.readText().trim() == markerVersion
        ) return

        installClientExecutables()
        installSystemProfileAssets()

        val desktopAsset = DesktopDefaults.applicationAssets.single {
            it.destination == "linuxhub-deb-installer.desktop"
        }
        val applications = File(paths.rootfsDir, "usr/share/applications").apply { mkdirs() }
        copyProfileAsset(desktopAsset, File(applications, desktopAsset.destination))

        val mimeApps = File(home, ".config/mimeapps.list")
        mimeApps.parentFile?.mkdirs()
        val original = if (mimeApps.isFile) mimeApps.readText() else ""
        val repaired = DesktopCompatibility.ensureDebMimeAssociations(original)
        if (repaired != original) mimeApps.writeText(repaired)
        mimeApps.setReadable(true, false)
        mimeApps.setWritable(true, true)

        // Nautilus resolves the desktop handler through mimeinfo.cache. Refresh
        // only that lightweight index; do not rebuild package or icon caches.
        val databaseReady = runCatching {
            runAsRoot(
                "if command -v update-desktop-database >/dev/null 2>&1; then " +
                    "update-desktop-database -q /usr/share/applications " +
                    ">/tmp/linuxhub-deb-installer-db.log 2>&1 || :; fi; exit 0",
                "deb-installer-desktop-database",
                timeoutMs = 30_000L,
                idleTimeoutMs = 10_000L,
            )
        }.onFailure {
            appendLog("WARN deb installer desktop database: ${it.message}")
        }.isSuccess
        if (databaseReady) {
            paths.desktopInstallerAssetsMarker.writeText("$markerVersion\n")
        }
    }

    private fun ensureUserDirectories(username: String) {
        val home = File(paths.rootfsDir, "home/$username")
        if (!home.isDirectory) return
        val config = File(home, ".config").apply { mkdirs() }
        val localShare = File(home, ".local/share").apply { mkdirs() }
        LinuxHubUserDirectories.directories.forEach { File(home, it).mkdirs() }

        File(config, "user-dirs.dirs").writeText(
            LinuxHubUserDirectories.userDirsFile(),
        )
        if (!File(config, "user-dirs.locale").isFile) {
            File(config, "user-dirs.locale").writeText(LinuxHubUserDirectories.locale + "\n")
        }
        val bookmarks = File(home, ".gtk-bookmarks")
        if (!bookmarks.isFile) {
            bookmarks.writeText(LinuxHubUserDirectories.bookmarksFile(username))
        }
        val recentlyUsed = File(localShare, "recently-used.xbel")
        if (!recentlyUsed.isFile) {
            recentlyUsed.writeText(LinuxHubUserDirectories.recentlyUsedFile())
        }

        val requiredDirectories = buildList {
            add(config)
            add(File(home, ".local"))
            addAll(LinuxHubUserDirectories.directories.map { File(home, it) })
        }
        val userDirectoriesRevision = "$USER_DIRECTORIES_VERSION:$username:" +
            LinuxHubUserDirectories.directories.joinToString("|")
        if (requiredDirectories.all(File::isDirectory) &&
            paths.userDirectoriesMarker.isFile &&
            paths.userDirectoriesMarker.readText().trim() == userDirectoriesRevision
        ) return

        val homePath = "/home/$username"
        val ownedPaths = buildList {
            add("$homePath/.config")
            add("$homePath/.local")
            add("$homePath/.gtk-bookmarks")
            addAll(LinuxHubUserDirectories.directories.map { "$homePath/$it" })
        }.joinToString(" ") { shell(it) }
        runAsRoot(
            "chown -R ${shell(username)}:${shell(username)} $ownedPaths; " +
                "chmod 700 ${shell("$homePath/.config")} ${shell("$homePath/.local")}; " +
                "find $ownedPaths -type d -exec chmod 755 {} +",
            "user-directories",
            timeoutMs = 20_000L,
            idleTimeoutMs = 5_000L,
        )
        paths.userDirectoriesMarker.writeText("$userDirectoriesRevision\n")
    }

    private fun ensureGnomeSessionAssets(username: String) {
        val share = File(paths.rootfsDir, "usr/local/share/linuxhub").apply { mkdirs() }
        val overlay = File(share, "gnome-shell-overlay")
        copyAsset(
            "desktop/gnome/overlay/org/gnome/shell/ui/main.js",
            File(overlay, "org/gnome/shell/ui/main.js"),
        )
        copyAsset(
            "desktop/gnome/overlay/org/gnome/shell/ui/layout.js",
            File(overlay, "org/gnome/shell/ui/layout.js"),
        )
        copyAsset(
            "desktop/gnome/overlay/org/gnome/shell/ui/sessionMode.js",
            File(overlay, "org/gnome/shell/ui/sessionMode.js"),
        )
        copyAsset(
            "desktop/gnome/overlay/ding/extension.js",
            File(
                paths.rootfsDir,
                "usr/share/gnome-shell/extensions/ding@rastersoft.com/extension.js",
            ),
        )
        copyAsset(
            "desktop/gnome/linuxhub-ubuntu-min.json",
            File(paths.rootfsDir, "usr/share/gnome-shell/modes/linuxhub-ubuntu-min.json"),
        )
        val defaults = File(share, "apply-ubuntu-defaults.sh")
        copyAsset("desktop/gnome/apply-ubuntu-defaults.sh", defaults)
        copyAsset(
            "desktop/gnome/linuxhub-system-bus.conf",
            File(share, "linuxhub-system-bus.conf"),
        )
        copyAsset(
            "desktop/gnome/linuxhub-session-bus.conf.in",
            File(share, "linuxhub-session-bus.conf.in"),
        )
        copyAsset(
            "desktop/gnome/pulseaudio.pa",
            File(share, "pulseaudio.pa"),
        )

        val legacyTrayMarker = File(paths.rootfsDir, ".doro-appindicator-no-legacy-tray-v1")
        if (!legacyTrayMarker.isFile) {
            runAsRoot(
                "su - ${shell(username)} -c " +
                    shell(
                        "dbus-run-session -- gsettings set " +
                            "org.gnome.shell.extensions.appindicator legacy-tray-enabled false",
                    ),
                "disable-legacy-tray",
                timeoutMs = 30_000L,
                idleTimeoutMs = 10_000L,
            )
            legacyTrayMarker.writeText("disabled\n")
        }

        val marker = File(paths.rootfsDir, ".doro-ubuntu-session-v2")
        if (marker.isFile) return
        runAsRoot(
            "test -f /usr/share/gnome-shell/theme/Yaru/gnome-shell-theme.gresource; " +
                "chmod 755 ${shell("/usr/local/share/linuxhub/apply-ubuntu-defaults.sh")}; " +
                "su - ${shell(username)} -c " +
                shell("dbus-run-session -- /usr/local/share/linuxhub/apply-ubuntu-defaults.sh"),
            "ubuntu-session-defaults",
            timeoutMs = 30_000L,
            idleTimeoutMs = 10_000L,
        )
        marker.writeText("ubuntu-session-v2\n")

    }

    /**
     * Applies the persisted route to the container. A hardware route is only
     * advertised after its host dependency is ready. If the VirGL server cannot
     * start, the route becomes SOFTWARE instead of launching a broken GL app.
     */
    private fun prepareGpuRuntime(
        requestedProfile: GpuProfile,
        enabled: Boolean = true,
    ): GpuRenderMode {
        val requestedMode = if (enabled) {
            DeviceGpuProbe.resolve(requestedProfile, paths.rootfsDir)
        } else {
            GpuRenderMode.SOFTWARE
        }
        var resolvedMode = requestedMode
        if (requestedMode == GpuRenderMode.VIRGL) {
            resolvedMode = runCatching {
                virglServer.start()
                appendLog("VIRGL server ready socket=${virglServer.socket.path}")
                requestedMode
            }.getOrElse { failure ->
                appendLog("WARN VirGL unavailable: ${failure.message}; falling back to SOFTWARE")
                GpuRenderMode.SOFTWARE
            }
        }
        File(paths.rootfsDir, "etc/doro-gpu-enabled").writeText(if (enabled) "1\n" else "0\n")
        File(paths.rootfsDir, GpuRuntimeEnvironment.MODE_FILE.removePrefix("/")).apply {
            parentFile?.mkdirs()
            writeText("${resolvedMode.id}\n")
        }
        File(paths.rootfsDir, "etc/doro-gpu-backend").writeText("${resolvedMode.id}\n")
        val profile = File(paths.rootfsDir, "etc/profile.d/doro-gpu.sh")
        profile.parentFile?.mkdirs()
        profile.writeText(GpuRuntimeEnvironment.profileScript())
        return resolvedMode
    }

    fun loginCommand(config: ContainerConfig = requireNotNull(loadConfig())): List<String> {
        // Terminal sessions are a separate entry point from the Compose home
        // screen. Deploy the APT/.deb compatibility files before the shell can
        // run its first package command. This is only a small asset copy; it
        // must never wait on startup maintenance or package locks.
        ensureDebPackageInstaller(config.username)
        installRuntime()
        ensureGetifaddrsBridge()
        val script = ensureAndroidGroupNamesScript() +
            "exec /usr/bin/su - ${shell(config.username)}"
        return prootCommand("/bin/sh", "-c", script)
    }

    /**
     * PRoot's fake-root extension intercepts getgroups(), so `id -G` does not expose the
     * vendor-specific supplementary groups inherited from the Android app process. Read the
     * kernel-maintained Groups line from /proc instead, then teach the Ubuntu group database
     * those names before su starts a login shell. Without this, Qualcomm builds print a long
     * "cannot find name for group ID" block when /etc/bash.bashrc probes for the sudo group.
     */
    private fun ensureAndroidGroupNamesScript(): String =
        "for gid in \$(sed -n 's/^Groups:[[:space:]]*//p' /proc/self/status); do " +
            "grep -q \"^[^:]*:[^:]*:\$gid:\" /etc/group || " +
            "printf 'android_gid_%s:x:%s:\\n' \"\$gid\" \"\$gid\" >> /etc/group; done; "

    fun terminalLaunchSpec(config: ContainerConfig = requireNotNull(loadConfig())): TerminalLaunchSpec {
        val command = loginCommand(config)
        return TerminalLaunchSpec(
            shellPath = command.first(),
            workingDirectory = sessionWorkingDirectory(),
            arguments = command.toTypedArray(),
            environment = loginEnvironment()
                .map { "${it.key}=${it.value}" }
                .toTypedArray()
        )
    }

    fun loginEnvironment(): Map<String, String> {
        paths.prootTmpDir.mkdirs()
        return mapOf(
            "LD_LIBRARY_PATH" to paths.libraryDir.path,
            "PROOT_LOADER" to paths.loader.path,
            "PROOT_TMP_DIR" to paths.prootTmpDir.path,
            "TMPDIR" to paths.prootTmpDir.path,
            "TERM" to "xterm-256color"
        )
    }

    fun sessionWorkingDirectory(): String = context.filesDir.path

    private fun ensureNetworkConfig() {
        File(paths.rootfsDir, "etc/resolv.conf").writeText(
            "nameserver 223.5.5.5\n" +
                "nameserver 119.29.29.29\n" +
                "nameserver 8.8.8.8\n" +
                "options timeout:2 attempts:3 rotate\n"
        )
        File(paths.rootfsDir, "etc/gai.conf").apply {
            parentFile?.mkdirs()
            writeText("precedence ::ffff:0:0/96  100\n")
        }
        File(paths.rootfsDir, "etc/wgetrc").writeText(
            "prefer-family = IPv4\ntimeout = 20\ntries = 3\n"
        )
        File(paths.rootfsDir, "etc/curlrc").writeText(
            "--ipv4\n--connect-timeout 20\n--retry 3\n--retry-delay 2\n"
        )
        File(paths.rootfsDir, "etc/apt/apt.conf.d/99doro-network").apply {
            parentFile?.mkdirs()
            writeText(
                "Acquire::ForceIPv4 \"true\";\n" +
                    "Acquire::Retries \"3\";\n" +
                    "Acquire::http::Timeout \"20\";\n" +
                    "Acquire::https::Timeout \"20\";\n"
            )
        }
    }

    /**
     * Starts the low-frequency PNG stream used by the browser remote display.
     * This deliberately uses the existing X11 root-window capture helper rather
     * than attaching an ImageReader to Lorie's SurfaceView, whose Surface is
     * owned by the Android compositor.
     */
    fun startRemoteFrameStream(): Process {
        ensureX11ControlAsset()
        return ProcessBuilder(
            prootCommand(
                "/bin/sh",
                "-c",
                "export DISPLAY=:0; exec /usr/local/bin/doro-x11-screenshot --display :0 --stream --fps 3",
            ),
        ).apply {
            environment().putAll(loginEnvironment())
            redirectErrorStream(false)
        }.start()
    }

    /**
     * Starts the persistent XTest input bridge. The server writes one JSON
     * object per line and keeps the process alive across browser pointer moves.
     */
    fun startRemoteInputDaemon(): Process {
        ensureX11ControlAsset()
        return ProcessBuilder(
            prootCommand(
                "/bin/sh",
                "-c",
                "export DISPLAY=:0; exec /usr/local/bin/doro-x11-input --display :0 --daemon",
            ),
        ).apply {
            environment().putAll(loginEnvironment())
            redirectErrorStream(false)
        }.start()
    }

    /**
     * The minimal Ubuntu rootfs has no CA bundle. Without it DNS and TCP work,
     * while every HTTPS client fails certificate verification. Install the two
     * small packages from the APK so both fresh and upgraded containers are
     * repaired without requiring a reinstall or network access.
     */
    private fun ensureTrustedCertificates() {
        if (!paths.rootfsReadyMarker.isFile) return
        val bundle = File(paths.rootfsDir, "etc/ssl/certs/ca-certificates.crt")
        if (bundle.isFile && bundle.length() > 0L) {
            paths.trustedCertificatesReadyMarker.writeText("$CA_CERTIFICATES_SHA256\n")
            return
        }
        synchronized(trustedCertificatesLock) {
            if (bundle.isFile && bundle.length() > 0L) return
            verifyAsset(OPENSSL_ASSET, OPENSSL_SHA256)
            verifyAsset(CA_CERTIFICATES_ASSET, CA_CERTIFICATES_SHA256)
            val opensslPackage = File(paths.rootfsDir, "tmp/$OPENSSL_FILE")
            val certificatesPackage = File(paths.rootfsDir, "tmp/$CA_CERTIFICATES_FILE")
            try {
                copyAsset(OPENSSL_ASSET, opensslPackage)
                copyAsset(CA_CERTIFICATES_ASSET, certificatesPackage)
                runAsRoot(
                    "export DEBIAN_FRONTEND=noninteractive UCF_FORCE_CONFFOLD=1 NEEDRESTART_MODE=a; " +
                        "dpkg --force-confdef --force-confold -i /tmp/$OPENSSL_FILE /tmp/$CA_CERTIFICATES_FILE; " +
                        "dpkg --force-confdef --force-confold --configure -a; " +
                        "command -v update-ca-certificates >/dev/null; " +
                        "update-ca-certificates --fresh; " +
                        "test -s /etc/ssl/certs/ca-certificates.crt",
                    "trusted-certificates",
                    timeoutMs = COMMAND_TIMEOUT_MS,
                    idleTimeoutMs = COMMAND_IDLE_TIMEOUT_MS,
                )
                paths.trustedCertificatesReadyMarker.writeText("$CA_CERTIFICATES_SHA256\n")
            } finally {
                opensslPackage.delete()
                certificatesPackage.delete()
            }
        }
    }

    /**
     * Installs small runtime libraries shared by vendor .deb applications.
     *
     * Some vendor packages declare incomplete dependencies. Keeping this
     * compatibility layer in the base container avoids per-application fixes:
     * every application sees the same system-wide libatomic1.
     */
    private fun ensureCommonRuntimePackages() {
        if (!paths.rootfsReadyMarker.isFile) return
        val library = File(paths.rootfsDir, "usr/lib/aarch64-linux-gnu/libatomic.so.1")
        val marker = File(paths.rootfsDir, "var/lib/doro/common-runtime.version")
        if (
            library.isFile &&
            marker.isFile &&
            marker.readText().trim() == COMMON_RUNTIME_MARKER_VERSION
        ) return

        synchronized(commonRuntimeLock) {
            if (
                library.isFile &&
                marker.isFile &&
                marker.readText().trim() == COMMON_RUNTIME_MARKER_VERSION
            ) return
            verifyAsset(COMMON_LIBATOMIC_ASSET, COMMON_LIBATOMIC_SHA256)
            val packageFile = File(paths.rootfsDir, "tmp/$COMMON_LIBATOMIC_FILE")
            packageFile.parentFile?.mkdirs()
            copyAsset(COMMON_LIBATOMIC_ASSET, packageFile)
            try {
                runAsRoot(
                    "if ! dpkg-query -W -f='\${Status}' libatomic1 2>/dev/null | " +
                        "grep -q 'install ok installed'; then " +
                        "dpkg --force-confdef --force-confold -i /tmp/$COMMON_LIBATOMIC_FILE; fi; " +
                        "ldconfig; " +
                        "test -e /usr/lib/aarch64-linux-gnu/libatomic.so.1",
                    "common-runtime-packages",
                    timeoutMs = COMMAND_TIMEOUT_MS,
                    idleTimeoutMs = COMMAND_IDLE_TIMEOUT_MS,
                )
                marker.parentFile?.mkdirs()
                marker.writeText("$COMMON_RUNTIME_MARKER_VERSION\n")
            } finally {
                packageFile.delete()
            }
        }
    }

    private fun ensureTunaAptMirror() {
        val aptDir = File(paths.rootfsDir, "etc/apt").apply { mkdirs() }
        File(aptDir, "sources.list").writeText(UbuntuAptSources.sourcesList())
        File(aptDir, "sources.list.d/ubuntu.sources").delete()
    }

    private fun ensureHostnameResolution() {
        File(paths.rootfsDir, "etc/hostname").writeText("localhost\n")
        val hosts = File(paths.rootfsDir, "etc/hosts")
        val retained = if (hosts.isFile) {
            hosts.readLines().filterNot { line ->
                val value = line.trim()
                value.startsWith("127.0.0.1") || value.startsWith("127.0.1.1")
            }
        } else emptyList()
        hosts.writeText(
            buildString {
                append("127.0.0.1 localhost\n")
                append("127.0.1.1 localhost\n")
                retained.forEach { append(it).append('\n') }
            }
        )
    }

    private fun ensureFetchScript() {
        val target = File(paths.rootfsDir, "usr/local/bin/doro-fetch")
        target.parentFile?.mkdirs()
        copyAsset(FETCH_SCRIPT_ASSET, target)
        target.setExecutable(true, false)
    }

    private fun installChineseTranslationPolicy() {
        File(paths.rootfsDir, "etc/dpkg/dpkg.cfg.d/zz-doro-translations").apply {
            parentFile?.mkdirs()
            writeText(
                "# Keep gettext catalogs for LinuxHub's supported UI language.\n" +
                    "path-include=/usr/share/locale/zh_CN/LC_MESSAGES/*.mo\n" +
                    "path-include=/usr/share/locale/zh_Hans/LC_MESSAGES/*.mo\n"
            )
        }
    }

    private fun ensureChineseTranslations() {
        val target = File(paths.rootfsDir, "usr/share")
        val required = listOf(
            "locale/zh_CN/LC_MESSAGES/xfce4-terminal.mo",
            "locale/zh_CN/LC_MESSAGES/thunar.mo",
            "locale/zh_CN/LC_MESSAGES/xfce4-panel.mo",
            "locale/zh_CN/LC_MESSAGES/xfwm4.mo",
            "locale/zh_CN/LC_MESSAGES/xfdesktop.mo",
            "locale/zh_CN/LC_MESSAGES/xfce4-settings.mo",
        ).map { File(target, it) }
        val marker = File(paths.rootfsDir, "var/lib/doro/assets/zh_CN-translations.tar.sha256")
        if (
            required.all(File::isFile) &&
            marker.isFile &&
            marker.readText().trim() == ZH_CN_TRANSLATIONS_SHA256
        ) return
        verifyAsset(ZH_CN_TRANSLATIONS_ASSET, ZH_CN_TRANSLATIONS_SHA256)
        context.assets.open(ZH_CN_TRANSLATIONS_ASSET).use { input ->
            TarGzExtractor.extract(input, target, ZH_CN_TRANSLATIONS_ASSET_SIZE)
        }
        check(required.all(File::isFile)) { "简体中文翻译资源不完整" }
        marker.parentFile?.mkdirs()
        marker.writeText("$ZH_CN_TRANSLATIONS_SHA256\n")
    }

    private fun ensureTerminalProfile(username: String) {
        val bashrc = File(paths.rootfsDir, "home/$username/.bashrc")
        if (!bashrc.isFile) return
        val marker = "# DORO_TERMINAL_PROFILE"
        var content = bashrc.readText()
        if (!content.contains(marker)) {
            bashrc.appendText(
                "\n$marker\n" +
                    "export LANG=zh_CN.UTF-8\n" +
                    "export LC_ALL=zh_CN.UTF-8\n" +
                    "export LANGUAGE=zh_CN:zh\n" +
                    "unset LD_PRELOAD\n" +
                    "export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/usr/games\n" +
                    "export TERM=xterm-256color\n" +
                    "export COLORTERM=truecolor\n" +
                    "export PS1='\\[\\e[1;32m\\]\\u@localhost\\[\\e[0m\\]:\\[\\e[1;34m\\]\\w\\[\\e[0m\\]\\$ '\n"
            )
        }
        content = bashrc.readText()
        if (!content.contains("export TERM=xterm-256color")) {
            bashrc.appendText("\nexport TERM=xterm-256color\nexport COLORTERM=truecolor\n")
        }
        val bannerMarker = "# DORO_UBUNTU_BANNER_V10"
        val bannerEndMarker = "# DORO_UBUNTU_BANNER_END"
        val current = bashrc.readText()
        val bannerStart = current.indexOf("# DORO_UBUNTU_BANNER_")
        val bannerEnd = current.indexOf(bannerEndMarker, bannerStart.coerceAtLeast(0))
        if (bannerStart >= 0 && bannerEnd >= bannerStart) {
            bashrc.writeText(current.removeRange(bannerStart, bannerEnd + bannerEndMarker.length))
        }
        bashrc.appendText(
            "\n$bannerMarker\n" +
                "if [ \"\$DORO_DESKTOP_TERMINAL\" != \"1\" ] && command -v fastfetch >/dev/null 2>&1; then\n" +
                "  doro-fetch\n" +
                "fi\n" +
                "$bannerEndMarker\n"
        )
    }

    private fun ensureDesktopWallpaper() {
        val xfceBackgrounds = File(paths.rootfsDir, "usr/share/backgrounds/xfce").apply { mkdirs() }
        val wallpaper = File(xfceBackgrounds, "doro-ubuntu.png")
        if (!wallpaper.isFile || wallpaper.length() == 0L) {
            copyAsset(DESKTOP_WALLPAPER_ASSET, wallpaper)
        }
    }

    private fun ensureDesktopProfile(username: String) {
        val home = File(paths.rootfsDir, "home/$username")
        if (!home.isDirectory) return
        synchronized(desktopProfileLock) {
            val profileRevision = installedProfileRevision()
            val currentRevision = runCatching { paths.desktopProfileMarker.readText().trim() }.getOrNull()
            if (currentRevision != profileRevision) {
                // App-owned defaults are synchronized once per installed APK.
                // Starting XFCE must not rewrite the user's profile or rebuild
                // package databases on every launch.
                installDesktopCompatibility()
                File(home, ".config/autostart/doro-sync-new-apps.desktop").delete()
                File(paths.rootfsDir, "usr/local/bin/doro-sync-new-apps").delete()
                removeConflictingNotificationArea(username)
                File(home, ".config/xfce4/panel")
                    .listFiles { file -> file.name.matches(Regex("genmon-.*\\.rc")) }
                    ?.forEach(File::delete)
                installStaticDesktopProfile(home)
                paths.desktopProfileMarker.parentFile?.mkdirs()
                paths.desktopProfileMarker.writeText("$profileRevision\n")
            }
            // Third-party packages can add launchers at any time. Rescanning is
            // idempotent and ensures those apps also receive the GPU wrapper.
            patchApplicationLaunchers(home)
            // LinuxHub-owned executables are shipped inside the APK and must
            // track the installed client even when the desktop profile revision
            // has not changed. They never contain user-edited state.
            DesktopDefaults.executableAssets.forEach { profileAsset ->
                copyProfileAsset(profileAsset, File(paths.rootfsDir, "usr/local/bin/${profileAsset.destination}"))
            }
            val panelProfile = File(
                home,
                ".config/xfce4/xfconf/xfce-perchannel-xml/xfce4-panel.xml",
            )
            if (panelProfile.isFile) {
                val original = panelProfile.readText()
                val repaired = DesktopCompatibility.repairBrokenPanelMaterial(original)
                if (repaired != original) panelProfile.writeText(repaired)
            }
            val windowManagerProfile = File(
                home,
                ".config/xfce4/xfconf/xfce-perchannel-xml/xfwm4.xml",
            )
            if (windowManagerProfile.isFile) {
                val original = windowManagerProfile.readText()
                val repaired = DesktopCompatibility.repairOpaqueWindowOpacity(original)
                if (repaired != original) windowManagerProfile.writeText(repaired)
            }
            val desktopProfile = File(
                home,
                ".config/xfce4/xfconf/xfce-perchannel-xml/xfce4-desktop.xml",
            )
            if (desktopProfile.isFile) {
                val original = desktopProfile.readText()
                val repaired = DesktopCompatibility.repairDynamicWallpaper(original)
                if (repaired != original) desktopProfile.writeText(repaired)
            }
            val terminalProfile = File(
                home,
                ".config/xfce4/xfconf/xfce-perchannel-xml/xfce4-terminal.xml",
            )
            if (terminalProfile.isFile) {
                val original = terminalProfile.readText()
                val repaired = DesktopCompatibility.repairBrokenTerminalMaterial(original)
                if (repaired != original) terminalProfile.writeText(repaired)
            }
            val mimeApps = File(home, ".config/mimeapps.list")
            val originalMimeApps = if (mimeApps.isFile) mimeApps.readText() else ""
            val repairedMimeApps = DesktopCompatibility.ensureDebMimeAssociations(originalMimeApps)
            if (repairedMimeApps != originalMimeApps) mimeApps.writeText(repairedMimeApps)
            File(home, "Desktop").mkdirs()
        }
    }

    private fun installedProfileRevision(): String {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        // lastUpdateTime invalidates APK-owned caches even when two development
        // builds reuse the same versionName/versionCode. Release builds still
        // carry an incremented versionCode.
        return "${info.versionName.orEmpty()}:$versionCode:${info.lastUpdateTime}"
    }

    private fun installClientExecutables() {
        DesktopDefaults.executableAssets.forEach { profileAsset ->
            copyProfileAsset(
                profileAsset,
                File(paths.rootfsDir, "usr/local/bin/${profileAsset.destination}"),
            )
        }
    }

    private fun installSystemProfileAssets() {
        DesktopDefaults.systemProfileAssets.forEach { profileAsset ->
            copyProfileAsset(profileAsset, File(paths.rootfsDir, profileAsset.destination))
        }
    }

    private fun installStaticDesktopProfile(home: File) {
        DesktopDefaults.homeProfileAssets.forEach { profileAsset ->
            val target = File(home, profileAsset.destination)
            if (!target.isFile || target.length() == 0L) {
                copyProfileAsset(profileAsset, target)
            }
        }
        DesktopDefaults.managedHomeProfileAssets.forEach { profileAsset ->
            copyProfileAsset(profileAsset, File(home, profileAsset.destination))
        }
        val applications = File(paths.rootfsDir, "usr/share/applications").apply { mkdirs() }
        DesktopDefaults.applicationAssets.forEach { profileAsset ->
            copyProfileAsset(profileAsset, File(applications, profileAsset.destination))
        }
        installClientExecutables()
        installSystemProfileAssets()
    }

    private fun copyProfileAsset(profileAsset: DesktopDefaults.ProfileAsset, target: File) {
        target.parentFile?.mkdirs()
        context.assets.open(profileAsset.asset).use { input ->
            target.outputStream().use(input::copyTo)
        }
        target.setReadable(true, false)
        target.setWritable(true, true)
        target.setExecutable(profileAsset.executable, false)
    }

    private fun patchApplicationLaunchers(home: File) {
        val applications = File(paths.rootfsDir, "usr/share/applications")
        val appGridDeleted = File(applications, "doro-app-grid.desktop").delete()
        val firefoxDeleted = File(applications, "firefox.desktop").delete()
        var launchersChanged = appGridDeleted || firefoxDeleted
        listOf(
            applications,
            File(home, "Desktop"),
            File(home, ".local/share/applications"),
            File(home, ".config/autostart"),
        ).forEach { directory ->
                directory.listFiles { file -> file.isFile && file.extension == "desktop" }
                    ?.forEach sourceLoop@ { source ->
                        val original = runCatching { source.readText() }.getOrNull()
                            ?: return@sourceLoop
                        var desktop = DesktopCompatibility.patchDesktop(original)
                        desktop = when (source.name) {
                            "google-chrome.desktop",
                            "com.google.Chrome.desktop" -> DesktopCompatibility.patchChromeDesktop(desktop)
                            "wechat.desktop" -> DesktopCompatibility.setDesktopEntryExec(
                                desktop,
                                "/usr/local/bin/doro-wechat %U",
                            )
                            else -> DesktopCompatibility.wrapApplicationWithGpu(
                                desktop,
                                source.name,
                            )
                        }
                        if (desktop != original) {
                            source.writeText(desktop)
                            launchersChanged = true
                        }
                    }
            }
        val desktopCache = File(applications, "mimeinfo.cache")
        if (launchersChanged || !desktopCache.isFile) {
            // Optional cache maintenance must never be able to abort XFCE.
            // PRoot can report SIGBUS from indexing helpers; desktop files are
            // still directly launchable without this cache.
            runCatching {
                runAsRoot(
                    "if command -v update-desktop-database >/dev/null 2>&1; then " +
                        "update-desktop-database /usr/share/applications " +
                        ">/tmp/doro-desktop-database.log 2>&1; fi; exit 0",
                    "desktop-database",
                    timeoutMs = 60_000L,
                    idleTimeoutMs = 20_000L,
                )
            }.onFailure {
                appendLog("WARN desktop database refresh skipped: ${it.message}")
            }
        }
    }

    private fun installDesktopThemes() {
        val themes = File(paths.rootfsDir, "usr/share/themes")
        installThemeAsset(
            asset = WHITESUR_GTK_ASSET,
            sha256 = WHITESUR_GTK_SHA256,
            totalBytes = WHITESUR_GTK_ASSET_SIZE,
            requiredPaths = listOf(File(themes, "WhiteSur-Dark/index.theme")),
            cleanPaths = listOf(File(themes, "WhiteSur-Dark")),
        )
        val icons = File(paths.rootfsDir, "usr/share/icons")
        // Tela Circle is the runnable theme with the complete icon set.
        // LinuxHub icons are overlaid into it; never create an incomplete
        // inheritance theme that can leave Plank with no drawable icon.
        val telaInstalled = installThemeAsset(
            asset = TELA_CIRCLE_ASSET,
            sha256 = TELA_CIRCLE_SHA256,
            totalBytes = TELA_CIRCLE_ASSET_SIZE,
            requiredPaths = listOf(
                File(icons, "Tela-circle/index.theme"),
                File(icons, "Tela-circle/scalable/apps/google-chrome.svg"),
                File(icons, "Tela-circle/22/places/folder.svg"),
                File(icons, "Tela-circle/22/panel/start-here.svg"),
            ),
            cleanPaths = listOf(File(icons, "Tela-circle")),
        )
        val linuxHubIconsInstalled = installThemeAsset(
            asset = LINUXHUB_ICONS_ASSET,
            sha256 = LINUXHUB_ICONS_SHA256,
            totalBytes = LINUXHUB_ICONS_ASSET_SIZE,
            requiredPaths = listOf(
                File(icons, "Tela-circle/scalable/apps/start-here.svg"),
                File(icons, "Tela-circle/scalable/places/folder.svg"),
                File(icons, "Tela-circle/scalable/places/user-home.svg"),
                File(icons, "Tela-circle/22/panel/start-here.svg"),
                File(icons, "Tela-circle/22/places/folder.svg"),
            ),
            cleanPaths = listOf(File(icons, "LinuxHub-Pixel"), File(icons, "Mignon-pastel")),
        )
        check(File(icons, "Tela-circle/index.theme").isFile) { "Tela Circle图标主题不完整" }
        val iconCache = File(icons, "Tela-circle/icon-theme.cache")
        if (telaInstalled || linuxHubIconsInstalled || !iconCache.isFile) {
            // Icon caches are optional. A stale/corrupt cache is worse than no cache:
            // GTK can always read the SVG files directly. Never let this maintenance
            // step abort XFCE startup.
            runAsRoot(
                "if command -v gtk-update-icon-cache >/dev/null 2>&1; then " +
                    "gtk-update-icon-cache -f -t /usr/share/icons/Tela-circle " +
                    ">/tmp/doro-icon-cache.log 2>&1 || rm -f /usr/share/icons/Tela-circle/icon-theme.cache; fi; " +
                    "exit 0",
                "linuxhub-icon-cache",
            )
        }
    }

    private fun installThemeAsset(
        asset: String,
        sha256: String,
        totalBytes: Long,
        requiredPaths: List<File> = emptyList(),
        cleanPaths: List<File> = emptyList(),
        force: Boolean = false,
    ): Boolean {
        val marker = File(
            paths.rootfsDir,
            "var/lib/doro/assets/${asset.substringAfterLast('/')}.sha256",
        )
        if (
            !force &&
            requiredPaths.isNotEmpty() &&
            requiredPaths.all(File::isFile) &&
            marker.isFile &&
            marker.readText().trim() == sha256
        ) return false
        verifyAsset(asset, sha256)
        cleanPaths.forEach { it.deleteRecursively() }
        context.assets.open(asset).use { input ->
            TarGzExtractor.extract(input, File(paths.rootfsDir, "usr/share"), totalBytes)
        }
        check(requiredPaths.all(File::isFile)) { "主题资源不完整：$asset" }
        marker.parentFile?.mkdirs()
        marker.writeText("$sha256\n")
        return true
    }

    private fun removeConflictingNotificationArea(username: String) {
        val autostart = File(paths.rootfsDir, "home/$username/.config/autostart").apply { mkdirs() }
        listOf("indicator-application.desktop", "indicator-messages.desktop").forEach { name ->
            val target = File(autostart, name)
            if (!target.isFile) {
                target.writeText(
                    "[Desktop Entry]\nType=Application\nName=Disabled $name\nHidden=true\n"
                )
            }
        }
        val sessions = File(paths.rootfsDir, "home/$username/.cache/sessions")
        sessions.deleteRecursively()
    }

    private fun installDesktopCompatibility() {
        val bin = File(paths.rootfsDir, "usr/local/bin").apply { mkdirs() }
        File(bin, "doro-start-xfce").delete()
        File(bin, "doro-recover-desktop").delete()
        File(bin, "doro-apply-wallpaper").delete()
        File(bin, "doro-apply-desktop-defaults").delete()
        File(bin, "xfce4-about").delete()
        File(bin, "doro-battery-status").apply {
            writeText(
                "#!/bin/sh\n" +
                    "value=\$(cat /run/doro-audio/battery-capacity 2>/dev/null || echo --)\n" +
                    "case \"\$value\" in ''|*[!0-9]*) icon=battery-missing-symbolic;; " +
                    "[0-9]|1[0-9]) icon=battery-empty-symbolic;; 2[0-9]|3[0-9]) icon=battery-caution-symbolic;; " +
                    "4[0-9]|5[0-9]|6[0-9]|7[0-9]) icon=battery-good-symbolic;; *) icon=battery-full-symbolic;; esac\n" +
                    "echo \"<icon>\$icon</icon><txt> \${value}%</txt><tool>电池电量：\${value}%</tool>\"\n"
            )
            setExecutable(true, false)
        }
        File(bin, "doro-chrome").apply {
            writeText(DoroChromeLauncher.script())
            setExecutable(true, false)
        }
        File(bin, "doro-wechat").apply {
            writeText(
                "#!/bin/sh\n" +
                    "export PULSE_SERVER=unix:\${XDG_RUNTIME_DIR}/pulse/native\n" +
                    "export QT_QPA_PLATFORM=xcb\n" +
                    "exec /usr/local/bin/doro-gpu-run /opt/wechat/wechat --no-sandbox \"\$@\"\n"
            )
            setExecutable(true, false)
        }
        File(bin, "doro-spark-store").delete()
        File(bin, "doro-libreoffice").delete()
    }

    private fun ensureSvgIconRuntime() {
        val loader = File(
            paths.rootfsDir,
            "usr/lib/aarch64-linux-gnu/gdk-pixbuf-2.0/2.10.0/loaders/libpixbufloader-svg.so",
        )
        val marker = File(paths.rootfsDir, ".doro-svg-icon-runtime-v1")
        if (loader.isFile && marker.isFile) return

        val packageFile = File(paths.rootfsDir, "tmp/$LIBRSVG_COMMON_FILE")
        packageFile.parentFile?.mkdirs()
        verifyAsset(LIBRSVG_COMMON_ASSET, LIBRSVG_COMMON_SHA256)
        copyAsset(LIBRSVG_COMMON_ASSET, packageFile)
        runAsRoot(
            "export DEBIAN_FRONTEND=noninteractive UCF_FORCE_CONFFOLD=1 NEEDRESTART_MODE=a; " +
                "dpkg --force-confdef --force-confold -i /tmp/$LIBRSVG_COMMON_FILE; " +
                "if [ -x /usr/lib/aarch64-linux-gnu/gdk-pixbuf-2.0/gdk-pixbuf-query-loaders ]; then " +
                "/usr/lib/aarch64-linux-gnu/gdk-pixbuf-2.0/gdk-pixbuf-query-loaders --update-cache; fi; " +
                "test -f /usr/lib/aarch64-linux-gnu/gdk-pixbuf-2.0/2.10.0/loaders/libpixbufloader-svg.so",
            "svg-icon-runtime",
            timeoutMs = COMMAND_TIMEOUT_MS,
        )
        packageFile.delete()
        marker.writeText("$LIBRSVG_COMMON_SHA256\n")
    }

    private fun removeDeprecatedSparkStore() {
        val marker = File(paths.rootfsDir, ".doro-spark-store-removed-v1")
        if (marker.isFile) return
        runAsRoot(
            "export DEBIAN_FRONTEND=noninteractive; " +
                "dpkg-query -W -f='\${Status}' spark-store 2>/dev/null | grep -q 'install ok installed' && " +
                "dpkg --purge --force-depends spark-store >/dev/null 2>&1 || true; " +
                "rm -rf /opt/spark-store; " +
                "rm -f /usr/share/applications/spark-store.desktop /usr/local/bin/doro-spark-store; " +
                "find /home -path '*/.local/share/applications/spark-store.desktop' -delete 2>/dev/null || true",
            "remove-spark-store",
            timeoutMs = COMMAND_TIMEOUT_MS,
        )
        marker.writeText("spark-store removed\n")
    }

    fun stopDesktopServices() {
        audioBridge.close()
        virglServer.close()
    }

    fun stopDesktopSession() {
        val config = loadConfig() ?: return
        runCatching {
            runAsRoot(
                GnomeDesktopCompatibility.stopScript(config.username),
                "stop-gnome-session",
                timeoutMs = 15_000L,
                idleTimeoutMs = 5_000L,
            )
        }.onFailure { appendLog("WARN GNOME stop failed: ${it.message}") }
    }

    fun startDesktop(display: String, gpuAcceleration: Boolean = true): Process {
        appendLog("DESKTOP_STAGE begin display=$display gpu=$gpuAcceleration")
        val config = requireNotNull(loadConfig()) { "Ubuntu 尚未初始化" }
        ensureInstalledPackages()
        ensureVendorRuntimeRepair()
        appendLog("DESKTOP_STAGE packages-ready")
        // Keep the client and server in lockstep after an APK update.  The
        // bridge is part of the global application environment, not a
        // per-app launcher workaround.
        installRuntime()
        ensureGetifaddrsBridge()
        ensureNetworkConfig()
        ensureTrustedCertificates()
        ensureSudoInstalled(config.username)
        removeLegacyGpuWrappers()
        val gpuCapability = prepareGpuRuntime(config.gpuProfile, gpuAcceleration)
        appendLog("GPU_CAPABILITY ${gpuCapability.id}")
        appendLog("GPU_RENDER_MODE ${gpuCapability.id}")
        ensureX11ControlAsset()
        ensureDebPackageInstaller(config.username)
        ensureUserDirectories(config.username)
        // The vendor overlay does not run dpkg postinst scripts. Reconcile
        // LinuxHub-owned launchers after every update so clicking an app in
        // GNOME uses the same wrapper that was verified from the terminal.
        installDesktopCompatibility()
        patchApplicationLaunchers(File(paths.rootfsDir, "home/${config.username}"))
        ensureGnomeSessionAssets(config.username)
        appendLog("DESKTOP_STAGE runtime-ready")

        // Start the host-side FIFO reader before the guest session starts
        // PulseAudio. This closes the first-open race for module-pipe-sink
        // without making desktop launch depend on a working AudioTrack.
        runCatching { audioBridge.start() }
            .onFailure { appendLog("WARN audio bridge unavailable: ${it.message}") }

        val script = "printf 'stage=session-user\\n' >>/tmp/doro-startup-stage.log 2>/dev/null || true; " +
            "export DORO_DESKTOP_TERMINAL=1; " +
            GnomeDesktopCompatibility.sessionScript(display, config.username, gpuCapability)
        val startSession = "printf 'stage=outer-root\\n' >>/tmp/doro-startup-stage.log 2>/dev/null || true; " +
            ensureAndroidGroupNamesScript() +
            GnomeDesktopCompatibility.systemServicesScript() + "; " +
            "printf 'stage=before-su\\n' >>/tmp/doro-startup-stage.log 2>/dev/null || true; " +
            "exec /usr/bin/su - ${shell(config.username)} -c ${shell(script)}"
        appendLog("DESKTOP_STAGE process-create")
        val process = ProcessBuilder(prootCommand("/bin/sh", "-c", startSession))
            .apply { environment().putAll(loginEnvironment()) }
            .redirectErrorStream(true)
            .start()
        appendLog("DESKTOP_STAGE process-started")
        return process
    }

    /** Executes a non-interactive command inside the initialized Ubuntu container. */
    fun executeContainerCommand(
        command: String,
        timeoutMs: Long = 30_000,
        maxOutputChars: Int = 200_000
    ): CommandExecutionResult {
        require(command.isNotBlank()) { "命令不能为空" }
        require(timeoutMs in 1_000..300_000) { "超时时间必须在 1-300 秒之间" }
        require(maxOutputChars in 1_024..1_000_000) { "输出上限必须在 1KB-1MB 之间" }
        val config = requireNotNull(loadConfig()) { "Ubuntu 尚未初始化" }
        // A terminal command may run before the asynchronous startup repair
        // finishes. Install the APT hook and .deb handler synchronously here
        // so the very first apt/dpkg operation already uses the shared path.
        ensureDebPackageInstaller(config.username)
        installRuntime()
        ensureGetifaddrsBridge()
        ensureSudoInstalled(config.username)
        ensureStartupMaintenance(config.username)
        val userCommand = "cd \"\$HOME\" 2>/dev/null || true; exec /bin/sh -lc ${shell(command)}"
        val loginScript = ensureAndroidGroupNamesScript() +
            "exec /usr/bin/su - ${shell(config.username)} -c ${shell(userCommand)}"
        val startedAt = System.nanoTime()
        val process = ProcessBuilder(prootCommand("/bin/sh", "-c", loginScript))
            .apply { environment().putAll(loginEnvironment()) }
            .redirectErrorStream(true)
            .start()
        val output = StringBuilder()
        var truncated = false
        val reader = Thread {
            runCatching {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        synchronized(output) {
                            if (output.length < maxOutputChars) {
                                val remaining = maxOutputChars - output.length
                                output.append(line.take(remaining)).append('\n')
                                if (line.length + 1 > remaining) truncated = true
                            } else {
                                truncated = true
                            }
                        }
                    }
                }
            }
        }.apply {
            name = "linuxhub-container-command"
            isDaemon = true
            start()
        }
        var timedOut = false
        while (process.isAlive) {
            if ((System.nanoTime() - startedAt) / 1_000_000 > timeoutMs) {
                timedOut = true
                process.destroy()
                Thread.sleep(300)
                if (process.isAlive) process.destroyForcibly()
                break
            }
            Thread.sleep(50)
        }
        reader.join(1_000)
        val exitCode = runCatching { process.exitValue() }.getOrDefault(if (timedOut) 124 else -1)
        return CommandExecutionResult(
            exitCode = exitCode,
            output = synchronized(output) { output.toString().trimEnd() },
            timedOut = timedOut,
            truncated = truncated,
            durationMs = (System.nanoTime() - startedAt) / 1_000_000
        )
    }

    private fun runAsRoot(
        script: String,
        operation: String = "configure",
        timeoutMs: Long = COMMAND_TIMEOUT_MS,
        idleTimeoutMs: Long = COMMAND_IDLE_TIMEOUT_MS,
        onActivity: (String) -> Unit = {}
    ): String {
        appendLog("COMMAND $operation")
        val process = ProcessBuilder(prootCommand("/bin/sh", "-c", script))
            .apply { environment().putAll(loginEnvironment()) }
            .redirectErrorStream(true).start()
        val output = StringBuilder()
        val watchdog = ProcessWatchdog(timeoutMs, idleTimeoutMs)
val reader = Thread {
            runCatching {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        watchdog.recordActivity()
                        synchronized(output) {
                            output.appendLine(line)
                            val overflow = output.length - MAX_CAPTURED_OUTPUT_CHARS
                            if (overflow > 0) output.delete(0, overflow)
                        }
                        appendLog("[$operation] $line")
                        onActivity(line)
                    }
                }
            }.onFailure { failure ->
                if (process.isAlive) appendLog("[$operation] output reader failed: ${failure.message}")
            }
        }.apply { name = "doro-$operation-output"; isDaemon = true; start() }
        var failure: String? = null
        while (true) {
            val code = runCatching { process.exitValue() }.getOrNull()
            if (code != null) break
            Thread.sleep(WATCHDOG_POLL_MS)
            failure = when {
                watchdog.isTotalTimedOut() -> "${operation}执行超时"
                watchdog.isIdleTimedOut() -> "${operation}长时间无输出"
                else -> null
            }
            if (failure != null) {
                appendLog("WATCHDOG $failure")
                process.destroy()
                Thread.sleep(PROCESS_KILL_GRACE_MS)
                break
            }
        }
        reader.join(PROCESS_KILL_GRACE_MS)
        check(failure == null) { "$failure，已终止进程。日志：${paths.initializationLogFile.path}" }
        val code = process.waitFor()
        val captured = synchronized(output) { output.toString() }
        check(code == 0) { captured.ifBlank { "Ubuntu 配置失败：$operation，退出码 $code" } }
return captured
    }
private fun prepareContainerMounts() {
        paths.x11SocketDir.mkdirs()
        paths.sharedMemoryDir.mkdirs()
        paths.audioDir.mkdirs()
        paths.desktopScreenshotDir.mkdirs()
        paths.sharedMemoryDir.setReadable(true, false)
        paths.sharedMemoryDir.setWritable(true, false)
        paths.sharedMemoryDir.setExecutable(true, false)
        paths.desktopScreenshotDir.setReadable(true, false)
        paths.desktopScreenshotDir.setWritable(true, false)
        paths.desktopScreenshotDir.setExecutable(true, false)
        paths.compatibilityProcDir.mkdirs()
        val procStat = runCatching { File("/proc/stat").readText() }.getOrNull()
        paths.compatibilityProcStat.writeText(
            procStat?.takeIf(String::isNotBlank)
                ?: "cpu  1 0 1 1 0 0 0 0 0 0\nbtime 0\n"
        )
        // Android exposes /proc/version as unreadable to the app sandbox.
        // LibreOffice probes it during startup, so provide a small compatible
        // file instead of letting the guest see EACCES.
        val procVersion = runCatching { File("/proc/version").readText() }.getOrNull()
        paths.compatibilityProcVersion.writeText(
            procVersion?.takeIf(String::isNotBlank)
                ?: "Linux version 6.1.0-android-linuxhub (linuxhub) #1 SMP PREEMPT\n"
        )
        // Android denies apps access to /proc/bus/pci/devices. Chromium treats
        // that denial as a fatal GPU-process error before trying EGL/Vulkan.
        // Adreno is not PCI-backed, so an accurate empty inventory is enough.
        paths.compatibilityPciDevices.writeText("")
        val battery = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val capacity = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).coerceIn(0, 100)
        val batteryDir = File(paths.compatibilityPowerSupplyDir, "BAT0").apply { mkdirs() }
        File(batteryDir, "type").writeText("Battery\n")
        File(batteryDir, "present").writeText("1\n")
        File(batteryDir, "capacity").writeText("$capacity\n")
        File(batteryDir, "status").writeText("Unknown\n")
        File(paths.audioDir, "battery-capacity").writeText("$capacity\n")
    }

    private fun androidStorageBindings(): List<String> {
        val primary = File("/storage/emulated/0")
        return if (primary.isDirectory && primary.canRead()) {
            listOf("-b", "${primary.path}:/sdcard", "-b", primary.path)
        } else {
            listOf("-b", "/sdcard")
        }
    }

    private fun sudoAccessScript(username: String): String =
        "getent group sudo >/dev/null || groupadd sudo; " +
            "usermod -aG sudo ${shell(username)}; " +
            // Older containers were extracted by a tar reader that reduced
            // privileged binaries to 0711 and discarded SUID/SGID. Repair the
            // standard Ubuntu privilege helpers once per marker version.
            "chown 0:0 /usr/bin/su /usr/bin/passwd /usr/bin/sudo; " +
            "chmod 4755 /usr/bin/su /usr/bin/passwd /usr/bin/sudo; " +
            "test -u /usr/bin/su; test -u /usr/bin/passwd; test -u /usr/bin/sudo; " +
            "mkdir -p /etc/sudoers.d; " +
            "printf '%s ALL=(ALL:ALL) ALL\\n' ${shell(username)} > /etc/sudoers.d/${shell(username)}; " +
            "chmod 440 /etc/sudoers.d/${shell(username)}; " +
            "user_hash=\$(getent shadow ${shell(username)} | cut -d: -f2); " +
            "root_hash=\$(getent shadow root | cut -d: -f2); " +
            "case \"\$root_hash\" in ''|'*'|'!'*) [ -n \"\$user_hash\" ] && printf 'root:%s\\n' \"\$user_hash\" | chpasswd -e || exit 1;; esac; " +
            "test -x /usr/bin/sudo; test -r /etc/sudoers.d/${shell(username)}"

    private fun prootCommand(vararg command: String): List<String> = buildList {
        add(paths.proot.path)
        prepareContainerMounts()
        addAll(listOf("--link2symlink", "-0", "-r", paths.rootfsDir.path,
            "-b", "/dev", "-b", "${paths.sharedMemoryDir.path}:/dev/shm",
            "-b", "/proc", "-b", "${paths.compatibilityProcVersion.path}:/proc/version",
            "-b", "${paths.compatibilityProcStat.path}:/proc/stat",
            "-b", "${paths.compatibilityPciDevices.path}:/proc/bus/pci/devices",
            "-b", "${paths.audioDir.path}:/run/doro-audio",
            "-b", "${paths.desktopScreenshotDir.path}:/run/doro-desktop",
            "-b", "/sys", "-b", "${paths.compatibilityPowerSupplyDir.path}:/sys/class/power_supply"))
        addAll(androidStorageBindings())
        addAll(listOf("-b", "${paths.x11SocketDir.path}:/tmp/.X11-unix",
            "-w", "/root", "/usr/bin/env", "-i", "HOME=/root", "USER=root",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/usr/games",
            "LANG=C.UTF-8", "TERM=xterm-256color", "COLORTERM=truecolor"))
        addAll(command)
    }

    private fun installRuntime() {
        val revision = installedProfileRevision()
        val installedClient = File(paths.rootfsDir, GetifaddrsBridge.CONTAINER_LIBRARY.removePrefix("/"))
        val profile = File(paths.rootfsDir, "etc/profile.d/doro.sh")
        val preloadLine = "export LD_PRELOAD=${GetifaddrsBridge.CONTAINER_LIBRARY}"
        val alreadyInstalled = runtimePreparedRevision == revision &&
            paths.talloc.isFile && paths.androidShmem.isFile &&
            File(paths.runtimeDir, "libgetifaddrs_bridge.so").isFile &&
            installedClient.isFile && profile.isFile &&
            profile.readLines().any { it.trim() == preloadLine }
        if (alreadyInstalled) {
            // The bridge is APK-owned and may change without changing the app
            // version during a debug/test install. Refresh it rather than
            // leaving an older C client in the rootfs.
            synchronized(runtimeInstallLock) { installGetifaddrsClient() }
            return
        }

        synchronized(runtimeInstallLock) {
            val retryInstalled = runtimePreparedRevision == revision &&
                paths.talloc.isFile && paths.androidShmem.isFile &&
                File(paths.runtimeDir, "libgetifaddrs_bridge.so").isFile &&
                installedClient.isFile && profile.isFile &&
                profile.readLines().any { it.trim() == preloadLine }
            if (retryInstalled) {
                installGetifaddrsClient()
                return
            }

            paths.prootTmpDir.mkdirs()
            check(paths.prootTmpDir.isDirectory && paths.prootTmpDir.canWrite()) { "PRoot临时目录不可写" }
            check(paths.proot.isFile && paths.proot.canExecute()) { "PRoot 原生运行时不可用" }
            check(paths.loader.isFile) { "PRoot Loader 不可用" }
            paths.libraryDir.mkdirs()
            copyAsset("runtime/arm64-v8a/lib/libtalloc.so.2", paths.talloc)
            copyAsset("runtime/arm64-v8a/lib/libandroid-shmem.so", paths.androidShmem)
            installGetifaddrsClient()
            profile.parentFile?.mkdirs()
            if (!profile.exists()) profile.writeText("$preloadLine\n")
            else if (profile.readLines().none { it.trim() == preloadLine }) profile.appendText("$preloadLine\n")
            check(paths.getifaddrsServer.isFile && paths.getifaddrsServer.canExecute()) { "getifaddrs服务端不可用" }
            runtimePreparedRevision = revision
        }
    }

    private fun installGetifaddrsClient() {
        val stagedClient = File(paths.runtimeDir, "libgetifaddrs_bridge.so")
        copyAsset(paths.getifaddrsClientAsset, stagedClient)
        GetifaddrsBridge.installClient(paths.rootfsDir, stagedClient)
    }

    private fun ensureGetifaddrsBridge() {
        synchronized(bridgeLock) {
            if (bridgeProcess?.let { runCatching { it.exitValue() }.isFailure } == true && paths.getifaddrsSocket.exists()) return
            bridgeProcess?.destroy()
            paths.getifaddrsSocket.parentFile?.mkdirs()
            paths.getifaddrsSocket.delete()
            // The lock is held by the server process, not by the socket.
            // Remove a stale lock left by a server orphaned before the
            // current app process took ownership.
            paths.getifaddrsSocket.parentFile?.let { parent ->
                File(parent, "${paths.getifaddrsSocket.name}.lock").delete()
            }
            val bridgeLog = File(paths.baseDir, "logs/getifaddrs.log").also { it.parentFile?.mkdirs() }
            bridgeProcess = ProcessBuilder(
                "/system/bin/sh", "-c",
                "exec ${shell(paths.getifaddrsServer.path)} ${shell(paths.getifaddrsSocket.path)} >> ${shell(bridgeLog.path)} 2>&1"
            ).start()
            repeat(40) {
                val process = bridgeProcess
                if (process != null && runCatching { process.exitValue() }.isSuccess) {
                    error("getifaddrs桥启动失败，服务端提前退出")
                }
                if (paths.getifaddrsSocket.exists()) return
                Thread.sleep(25)
            }
            error("getifaddrs桥启动失败")
        }
    }

    private fun copyAsset(asset: String, target: File) {
        target.parentFile?.mkdirs()
        val temporary = File(
            target.parentFile,
            "${target.name}.tmp-${android.os.Process.myPid()}-${Thread.currentThread().id}-${System.nanoTime()}",
        )
        try {
            context.assets.open(asset).use { input -> temporary.outputStream().use(input::copyTo) }
            check(temporary.renameTo(target)) { "运行时资源替换失败：${target.name}" }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun verifyAsset(asset: String, expectedSha256: String) {
        val digest = MessageDigest.getInstance("SHA-256")
        context.assets.open(asset).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        check(actual == expectedSha256) { "内置RootFS校验失败" }
    }

    private fun installMesaGpuRuntime() {
        if (!MesaGpuRuntime.isReady(paths.rootfsDir, paths.mesaGpuReadyMarker)) {
            appendLog("MESA runtime repair: marker exists but critical files do not match")
            verifyAsset(MESA_GPU_ASSET, MESA_GPU_SHA256)
            context.assets.open(MESA_GPU_ASSET).use { input ->
                TarGzExtractor.extract(input, paths.rootfsDir, MESA_GPU_ASSET_SIZE)
            }
            runAsRoot(
                "rm -rf /root/.cache/mesa_shader_cache /home/*/.cache/mesa_shader_cache; " +
                    "rm -f /.doro-mesa-gpu-26.2-isolated-v2-ready " +
                    "/usr/lib/aarch64-linux-gnu/libgallium-26.2.0-devel.so; " +
                    "ldconfig && " +
                    "test -f /usr/lib/aarch64-linux-gnu/libgallium-27.0.0-devel.so && " +
                    "test -f /usr/lib/aarch64-linux-gnu/libvulkan_freedreno.so && " +
                    "test -f /usr/lib/aarch64-linux-gnu/dri/zink_dri.so && " +
                    "test -f /usr/lib/aarch64-linux-gnu/dri/virtio_gpu_dri.so && " +
                    "test -f /usr/lib/aarch64-linux-gnu/dri/termuxva_drv_video.so && " +
                    "test -f /usr/share/vulkan/icd.d/freedreno_icd.aarch64.json",
                "mesa-gpu-runtime"
            )
            check(MesaGpuRuntime.filesReady(paths.rootfsDir, MesaGpuRuntime.criticalFiles)) {
                "Mesa 27运行时不完整：关键DRI/Gallium文件校验失败"
            }
            paths.mesaGpuReadyMarker.writeText(MesaGpuRuntime.VERSION + "\n")
        }
        removeLegacyGpuWrappers()
    }

    private fun removeLegacyGpuWrappers() {
        val wrapperDir = File(paths.rootfsDir, "usr/local/lib/doro-software-bin").apply { mkdirs() }
        listOf("xfwm4", "xfdesktop", "xfce4-panel", "plank").forEach { command ->
            File(wrapperDir, command).delete()
        }
    }

    private fun installGnomePackages(onProgress: (InitializationProgress) -> Unit) {
        if (reconcileInstalledGnomeProfile()) return

        paths.offlinePackagesDir.deleteRecursively()
        paths.offlinePackagesDir.mkdirs()
        verifyAsset(GNOME_ASSET, GNOME_SHA256)
        extractAsset(
            GNOME_ASSET,
            paths.offlinePackagesDir,
            InitializationProgressPlan.GNOME_ARCHIVE_START,
            InitializationProgressPlan.GNOME_ARCHIVE_END,
            "正在准备桌面组件",
            onProgress,
        )
        copyAsset(
            GNOME_PACKAGE_LIST_ASSET,
            File(paths.offlinePackagesDir, GNOME_PACKAGE_LIST),
        )
        val totalPackages = runCatching {
            File(paths.offlinePackagesDir, GNOME_PACKAGE_LIST)
                .readLines()
                .count(String::isNotBlank)
        }.getOrDefault(0)
        val processedPackageNames = linkedSetOf<String>()
        onProgress(createContainerProgress(
            "正在安装桌面、中文环境和常用应用",
            InitializationProgressPlan.GNOME_PACKAGES_START,
        ))
        runAsRoot(
            OfflineInstallScript.build(GNOME_PACKAGE_LIST),
            "gnome-packages",
            timeoutMs = OFFLINE_INSTALL_TIMEOUT_MS,
            idleTimeoutMs = OFFLINE_INSTALL_IDLE_TIMEOUT_MS,
            onActivity = { line ->
                val action = OfflineInstallProgress.parse(line)
                if (action?.countsAsProcessedPackage == true) {
                    processedPackageNames += action.packageKey
                }
                val processedPackages = processedPackageNames.size.coerceAtMost(totalPackages)
                val packageFraction = if (totalPackages > 0) {
                    val start = InitializationProgressPlan.GNOME_PACKAGES_START / 100f
                    val end = InitializationProgressPlan.GNOME_PACKAGES_END / 100f
                    start + ((end - start) * processedPackages / totalPackages)
                } else {
                    InitializationProgressPlan.GNOME_PACKAGES_START / 100f
                }
                onProgress(
                    createContainerProgress(
                        OfflineInstallProgress.message(action, totalPackages, processedPackages),
                        (packageFraction * 100f).toInt(),
                        packageFraction,
                    ),
                )
            },
        )
        onProgress(createContainerProgress(
            "正在完成桌面组件",
            InitializationProgressPlan.GNOME_COMPLETE,
        ))

        ensureTunaAptMirror()
        ensureHostnameResolution()
        runAsRoot(
            "sed -i 's/^# *zh_CN.UTF-8 UTF-8/zh_CN.UTF-8 UTF-8/' /etc/locale.gen; " +
                "locale-gen zh_CN.UTF-8; " +
                "update-locale LANG=zh_CN.UTF-8 LANGUAGE=zh_CN:zh LC_ALL=zh_CN.UTF-8; " +
                "locale -a | grep -qi '^zh_CN\\.utf8$'",
            "gnome-locale",
        )
        runAsRoot(GnomeInstallCheckScript.check(), "gnome-packages-check")
        ensureYaruIconTheme()
        paths.offlinePackagesDir.deleteRecursively()
        paths.gnomeReadyMarker.writeText("$GNOME_MARKER_VERSION\n")
    }

    /**
     * Desktop assets are versioned independently from the installed package set.
     * A changed APK asset must not reinstall every package in an existing desktop.
     */
    private fun reconcileInstalledGnomeProfile(): Boolean {
        val installedVersion = runCatching {
            paths.gnomeReadyMarker.readText().trim()
        }.getOrNull().orEmpty()
        if (installedVersion == GNOME_MARKER_VERSION) return true
        if (!installedVersion.startsWith(GNOME_MARKER_PREFIX)) return false

        val pendingDpkgJournal = File(paths.rootfsDir, "var/lib/dpkg/updates/tmp.i")
        if (pendingDpkgJournal.isFile) {
            runAsRoot(
                "export DEBIAN_FRONTEND=noninteractive UCF_FORCE_CONFFOLD=1 NEEDRESTART_MODE=a; " +
                    "dpkg --force-confdef --force-confold --configure -a",
                "gnome-dpkg-recovery",
                timeoutMs = OFFLINE_INSTALL_TIMEOUT_MS,
                idleTimeoutMs = OFFLINE_INSTALL_IDLE_TIMEOUT_MS,
            )
        }
        ensureYaruIconTheme()
        paths.gnomeReadyMarker.writeText("$GNOME_MARKER_VERSION\n")
        return true
    }

    private fun ensureYaruIconTheme() {
        val ready = runCatching {
            runAsRoot(
                "dpkg-query -W -f='\${Status}' yaru-theme-icon 2>/dev/null | " +
                    "grep -qx 'install ok installed' && " +
                    "test -e /usr/share/icons/Yaru/index.theme",
                "yaru-theme-check",
                timeoutMs = 60_000L,
                idleTimeoutMs = 30_000L,
            )
        }.isSuccess
        if (ready) {
            ensureYaruAppGridIcon()
            return
        }

        val packageFile = File(paths.rootfsDir, "tmp/$YARU_THEME_ICON_PACKAGE_FILE")
        packageFile.parentFile?.mkdirs()
        context.assets.open(GNOME_ASSET).use { input ->
            TarGzExtractor.extractEntry(
                source = input,
                destination = packageFile,
                entryName = "packages/$YARU_THEME_ICON_PACKAGE_FILE",
                totalBytes = GNOME_ASSET_SIZE,
            )
        }
        try {
            runAsRoot(
                "export DEBIAN_FRONTEND=noninteractive UCF_FORCE_CONFFOLD=1 NEEDRESTART_MODE=a; " +
                    "dpkg --force-confdef --force-confold -i /tmp/$YARU_THEME_ICON_PACKAGE_FILE; " +
                    "dpkg --force-confdef --force-confold --configure -a; " +
                    "test -z \"\$(dpkg -C 2>&1)\"",
                "yaru-theme-icon",
                timeoutMs = OFFLINE_INSTALL_TIMEOUT_MS,
                idleTimeoutMs = OFFLINE_INSTALL_IDLE_TIMEOUT_MS,
            )
        } finally {
            packageFile.delete()
        }
        ensureYaruAppGridIcon()
    }

    private fun ensureYaruAppGridIcon() {
        runAsRoot(
            "if [ -f /usr/share/icons/Yaru/scalable/actions/view-app-grid-ubiquity-symbolic.svg ]; then " +
                "cp -f /usr/share/icons/Yaru/scalable/actions/view-app-grid-ubiquity-symbolic.svg " +
                "/usr/share/icons/Yaru/scalable/actions/view-app-grid-linuxhub-ubuntu-min-symbolic.svg; " +
                "if command -v gtk-update-icon-cache >/dev/null 2>&1; then " +
                "gtk-update-icon-cache -f -t /usr/share/icons/Yaru " +
                ">/tmp/doro-yaru-icon-cache.log 2>&1 || true; fi; fi",
            "yaru-app-grid-icon",
            timeoutMs = 60_000L,
            idleTimeoutMs = 30_000L,
        )
    }

    /** Legacy 1.x bootstrap installer retained for source-level rollback. */
    private fun installBootstrapPackages(onProgress: (InitializationProgress) -> Unit) {
        val installedVersion = runCatching {
            paths.bootstrapReadyMarker.readText().trim()
        }.getOrNull()
        if (installedVersion == BOOTSTRAP_MARKER_VERSION) return

        paths.offlinePackagesDir.deleteRecursively()
        paths.offlinePackagesDir.mkdirs()
        verifyAsset(BOOTSTRAP_ASSET, BOOTSTRAP_SHA256)
        extractAsset(
            BOOTSTRAP_ASSET,
            paths.offlinePackagesDir,
            35,
            44,
            "正在准备应用组件",
            onProgress
        )
        copyAsset(
            BOOTSTRAP_PACKAGE_LIST_ASSET,
            File(paths.offlinePackagesDir, BOOTSTRAP_PACKAGE_LIST)
        )
        onProgress(createContainerProgress("正在安装桌面和常用应用", 44))
        runAsRoot(
            OfflineInstallScript.build(BOOTSTRAP_PACKAGE_LIST),
            "bootstrap-packages",
            timeoutMs = OFFLINE_INSTALL_TIMEOUT_MS,
            idleTimeoutMs = OFFLINE_INSTALL_IDLE_TIMEOUT_MS,
            onActivity = { line ->
                onProgress(createContainerProgress("正在配置应用组件", 44))
            }
        )

        ensureTunaAptMirror()
        ensureHostnameResolution()
        runAsRoot(
            "sed -i 's/^# *zh_CN.UTF-8 UTF-8/zh_CN.UTF-8 UTF-8/' /etc/locale.gen; " +
                "locale-gen zh_CN.UTF-8; " +
                "update-locale LANG=zh_CN.UTF-8 LANGUAGE=zh_CN:zh LC_ALL=zh_CN.UTF-8; " +
                "locale -a | grep -qi '^zh_CN\\.utf8$'",
            "chinese-locale"
        )
        runAsRoot(
            "command -v sudo curl file less zip unzip bzip2 perl vim top killall ifconfig " +
                "aria2c strace sl dig ping traceroute whois socat nc glxinfo vulkaninfo " +
                "startxfce4 fcitx5 thunar mousepad xfce4-terminal plank bluetoothctl nmcli >/dev/null; " +
                "test -f /usr/share/applications/thunar.desktop; " +
                "test -f /usr/share/applications/xfce4-terminal.desktop; " +
                "test -f /usr/lib/aarch64-linux-gnu/gdk-pixbuf-2.0/2.10.0/loaders/libpixbufloader-svg.so; " +
                "/usr/lib/aarch64-linux-gnu/gdk-pixbuf-2.0/gdk-pixbuf-query-loaders --update-cache; " +
                "grep -q 'libpixbufloader-svg.so' /usr/lib/aarch64-linux-gnu/gdk-pixbuf-2.0/2.10.0/loaders.cache; " +
                "test -z \"\$(dpkg -C 2>&1)\"",
            "bootstrap-packages-check"
        )
        paths.offlinePackagesDir.deleteRecursively()
        paths.bootstrapReadyMarker.writeText("$BOOTSTRAP_MARKER_VERSION\n")
    }

    private fun verifyGpuRuntime() {
        runAsRoot(
            "command -v glxinfo vulkaninfo >/dev/null && " +
                "test -f /usr/lib/aarch64-linux-gnu/libvulkan_freedreno.so && " +
                "test -f /usr/lib/aarch64-linux-gnu/dri/zink_dri.so && " +
                "test -f /usr/share/vulkan/icd.d/freedreno_icd.aarch64.json",
            "gpu-runtime-check"
        )
    }

    private fun installFastfetch(onProgress: (InitializationProgress) -> Unit) {
        if (paths.fastfetchReadyMarker.isFile && runCatching {
                runAsRoot("command -v fastfetch >/dev/null", "fastfetch-check")
            }.isSuccess) return
        onProgress(createContainerProgress(
            "正在准备终端",
            InitializationProgressPlan.FASTFETCH,
        ))
        val packageFile = File(paths.rootfsDir, "tmp/$FASTFETCH_FILE")
        packageFile.parentFile?.mkdirs()
        verifyAsset(FASTFETCH_ASSET, FASTFETCH_SHA256)
        copyAsset(FASTFETCH_ASSET, packageFile)
        runAsRoot(
            "export DEBIAN_FRONTEND=noninteractive UCF_FORCE_CONFFOLD=1; dpkg --force-confdef --force-confold -i /tmp/$FASTFETCH_FILE; command -v fastfetch >/dev/null",
            "fastfetch",
            timeoutMs = OFFLINE_INSTALL_TIMEOUT_MS
        )
        packageFile.delete()
        paths.fastfetchReadyMarker.writeText("fastfetch-2.66.0\n")
    }

    private fun installVendorApps(onProgress: (InitializationProgress) -> Unit) {
        val installedVersion = runCatching { paths.vendorAppsReadyMarker.readText().trim() }.getOrNull()
        if (!VendorAppsUpgradePolicy.requiresInstall(installedVersion, VENDOR_APPS_SHA256)) return
        appendLog("VENDOR_APPS migration-start previous=${installedVersion ?: "none"}")
        paths.vendorRuntimeRepairMarker.delete()
        paths.offlinePackagesDir.deleteRecursively()
        paths.offlinePackagesDir.mkdirs()
        verifyAsset(VENDOR_APPS_ASSET, VENDOR_APPS_SHA256)
        extractAsset(
            VENDOR_APPS_ASSET,
            paths.offlinePackagesDir,
            InitializationProgressPlan.VENDOR_ARCHIVE_START,
            InitializationProgressPlan.VENDOR_ARCHIVE_END,
            "正在解压桌面应用离线包",
            onProgress,
        )
        copyAsset(
            VENDOR_PACKAGE_LIST_ASSET,
            File(paths.offlinePackagesDir, VENDOR_PACKAGE_LIST),
        )
        val applicationTasks = VendorAppTasks.parse(
            File(paths.offlinePackagesDir, VENDOR_APP_TASKS_FILE).readText(),
        )
        val dependencyFiles = VendorAppTasks.parseDependencies(
            File(paths.offlinePackagesDir, VENDOR_DEPENDENCIES_FILE).readText(),
        )
        var tasks = buildList {
            add(
                InstallationTaskProgress(
                    key = VENDOR_DEPENDENCY_TASK_KEY,
                    label = "应用共享运行库",
                    detail = "${dependencyFiles.size} 个组件",
                    idleTimeoutSeconds = (OFFLINE_INSTALL_IDLE_TIMEOUT_MS / 1_000L).toInt(),
                ),
            )
            addAll(
                applicationTasks.map {
                    InstallationTaskProgress(
                        key = it.key,
                        label = it.label,
                        detail = if (it.packageFiles.size == 1) {
                            it.packageFiles.single()
                        } else {
                            "${it.packageFiles.size} 个安装包"
                        },
                    )
                },
            )
            add(
                InstallationTaskProgress(
                    key = VENDOR_REFRESH_TASK_KEY,
                    label = "刷新应用菜单和系统缓存",
                    detail = "图标、桌面入口、MIME 与字体",
                ),
            )
            add(
                InstallationTaskProgress(
                    key = VENDOR_VERIFY_TASK_KEY,
                    label = "桌面应用完整性校验",
                    detail = "检查可执行文件、运行库和桌面入口",
                ),
            )
        }
        var completedTasks = 0
        var activeTaskKey: String? = null
        val failures = mutableListOf<String>()

        fun publishTasks(message: String? = null) {
            val start = InitializationProgressPlan.VENDOR_TASKS_START / 100f
            val end = InitializationProgressPlan.VENDOR_TASKS_END / 100f
            val fraction = start + ((end - start) * completedTasks / tasks.size.coerceAtLeast(1))
            onProgress(
                createContainerProgress(
                    message ?: "正在部署应用（$completedTasks/${tasks.size}）",
                    (fraction * 100f).toInt(),
                    fraction,
                    tasks,
                ),
            )
        }

        fun updateTask(
            key: String,
            status: InstallationTaskStatus,
            detail: String,
        ) {
            tasks = tasks.map { task ->
                if (task.key == key) {
                    task.copy(
                        status = status,
                        detail = detail,
                        updatedAtMs = System.nanoTime() / 1_000_000L,
                    )
                } else {
                    task
                }
            }
            activeTaskKey = key.takeIf { status == InstallationTaskStatus.RUNNING }
            publishTasks()
        }

        fun installTask(
            task: InstallationTaskProgress,
            script: String,
            timeoutMs: Long,
            idleTimeoutMs: Long,
        ) {
            ensureMergedUsrLayout(onProgress = null)
            updateTask(
                task.key,
                InstallationTaskStatus.RUNNING,
                "${task.detail.ifBlank { "准备执行" }}，看门狗已启动",
            )
            try {
                runAsRoot(
                    script,
                    "vendor-${task.key}",
                    timeoutMs = timeoutMs,
                    idleTimeoutMs = idleTimeoutMs,
                    onActivity = { line ->
                        if (activeTaskKey == task.key && line.isNotBlank()) {
                            updateTask(
                                task.key,
                                InstallationTaskStatus.RUNNING,
                                line.trim().take(72),
                            )
                        }
                    },
                )
                updateTask(task.key, InstallationTaskStatus.SUCCESS, "安装完成")
            } catch (error: Throwable) {
                val message = error.message.orEmpty().ifBlank { "未知安装错误" }
                val timedOut = message.contains("超时") || message.contains("无输出")
                val failureDetail = summarizeInstallFailure(message, timedOut)
                updateTask(
                    task.key,
                    if (timedOut) InstallationTaskStatus.TIMEOUT else InstallationTaskStatus.FAILED,
                    failureDetail,
                )
                failures += "${task.label}：$failureDetail"
            } finally {
                completedTasks++
                publishTasks()
            }
        }

        val systemTaskCount = tasks.size - applicationTasks.size
        publishTasks(
            "准备部署 ${tasks.size} 个任务（${applicationTasks.size} 个应用 + ${systemTaskCount} 个系统步骤）",
        )
        val dependencyTask = tasks.first { it.key == VENDOR_DEPENDENCY_TASK_KEY }
        installTask(
            dependencyTask,
            vendorExtractScript(dependencyFiles),
            timeoutMs = OFFLINE_INSTALL_TIMEOUT_MS,
            idleTimeoutMs = OFFLINE_INSTALL_IDLE_TIMEOUT_MS,
        )
        applicationTasks.forEach { publishedTask ->
            val task = tasks.first { it.key == publishedTask.key }
            installTask(
                task,
                vendorExtractScript(publishedTask.packageFiles),
                timeoutMs = VENDOR_APP_TIMEOUT_MS,
                idleTimeoutMs = VENDOR_APP_IDLE_TIMEOUT_MS,
            )
        }
        ensureMergedUsrLayout(onProgress = null)
        installTask(
            tasks.first { it.key == VENDOR_REFRESH_TASK_KEY },
            vendorRefreshScript(),
            timeoutMs = OFFLINE_INSTALL_TIMEOUT_MS,
            idleTimeoutMs = OFFLINE_INSTALL_IDLE_TIMEOUT_MS,
        )
        installTask(
            tasks.first { it.key == VENDOR_VERIFY_TASK_KEY },
            VendorAppsCheckScript.check(),
            timeoutMs = OFFLINE_INSTALL_TIMEOUT_MS,
            idleTimeoutMs = OFFLINE_INSTALL_IDLE_TIMEOUT_MS,
        )
        if (failures.isNotEmpty()) {
            publishTasks("应用部署失败 ${failures.size} 项")
            throw IllegalStateException(
                "应用部署未完成（${failures.size} 项）：\n${failures.joinToString("\n")}",
            )
        }
        val completedFraction = InitializationProgressPlan.VENDOR_COMPLETE / 100f
        onProgress(createContainerProgress(
            "全部应用部署完成",
            InitializationProgressPlan.VENDOR_COMPLETE,
            completedFraction,
            tasks,
        ))
        paths.offlinePackagesDir.deleteRecursively()
        paths.vendorAppsReadyMarker.writeText(
            "${VendorAppsUpgradePolicy.marker(VENDOR_APPS_SHA256)}\n",
        )
        appendLog("VENDOR_APPS migration-complete")
    }

    private fun vendorExtractScript(files: List<String>): String = buildString {
        append("set -eu; ")
        append("tar --help 2>/dev/null | grep -q -- '--keep-directory-symlink' || ")
        append("{ printf '系统 tar 不支持合并 /usr 安全解包\\n' >&2; exit 1; }; ")
        files.forEach { file ->
            append("printf '展开 %s\\n' ")
            append(shell(file.removeSuffix(".deb")))
            append("; /usr/bin/dpkg-deb --fsys-tarfile ")
            append(shell("$OFFLINE_PACKAGES_PATH/$file"))
            append(" | tar --keep-directory-symlink --same-owner -xpf - -C /; ")
        }
        append("ln -sfn /opt/google/chrome/google-chrome /usr/local/bin/google-chrome")
    }

    private fun vendorRefreshScript(): String =
        "set -eu; " +
            "ln -sfn /opt/google/chrome/google-chrome /usr/local/bin/google-chrome; " +
            "test -x /usr/local/bin/doro-refresh-launchers || " +
            "{ printf '缺少统一桌面入口修补器\n' >&2; exit 1; }; " +
            "/usr/local/bin/doro-refresh-launchers; " +
            "mkdir -p /etc/ca-certificates /etc/ssl/certs; " +
            "find /usr/share/ca-certificates -type f -name '*.crt' | " +
            "sed 's#^/usr/share/ca-certificates/##' | LC_ALL=C sort > /etc/ca-certificates.conf; " +
            "test -s /etc/ca-certificates.conf; " +
            "command -v update-ca-certificates >/dev/null 2>&1 || { printf '缺少 update-ca-certificates\\n' >&2; exit 1; }; " +
            "update-ca-certificates --fresh; " +
            "test -s /etc/ssl/certs/ca-certificates.crt; " +
            "command -v update-desktop-database >/dev/null 2>&1 && update-desktop-database -q /usr/share/applications || :; " +
            "command -v gtk-update-icon-cache >/dev/null 2>&1 && gtk-update-icon-cache -q -t -f /usr/share/icons/hicolor || :; " +
            "command -v glib-compile-schemas >/dev/null 2>&1 && glib-compile-schemas /usr/share/glib-2.0/schemas || :; " +
            "command -v update-mime-database >/dev/null 2>&1 && update-mime-database /usr/share/mime || :; " +
            "command -v gdk-pixbuf-query-loaders >/dev/null 2>&1 && gdk-pixbuf-query-loaders > /usr/lib/aarch64-linux-gnu/gdk-pixbuf-2.0/2.10.0/loaders.cache 2>/dev/null || :; " +
            "command -v fc-cache >/dev/null 2>&1 && fc-cache -fs >/dev/null 2>&1 || :; " +
            VendorRuntimeRepair.script()

    private fun extractAsset(
        asset: String,
        destination: File,
        startPercent: Int,
        endPercent: Int,
        message: String,
        onProgress: (InitializationProgress) -> Unit
    ) {
        val assetSize = when (asset) {
            GNOME_ASSET -> GNOME_ASSET_SIZE
            BOOTSTRAP_ASSET -> BOOTSTRAP_ASSET_SIZE
            VENDOR_APPS_ASSET -> VENDOR_APPS_ASSET_SIZE
            else -> 0L
        }
        context.assets.open(asset).use { input ->
            TarGzExtractor.extract(input, destination, assetSize) { archivePercent ->
                val percent = startPercent + ((endPercent - startPercent) * archivePercent / 100)
                onProgress(createContainerProgress("$message $archivePercent%", percent))
            }
        }
    }

    private fun createContainerProgress(
        message: String,
        percent: Int,
        fraction: Float? = null,
        tasks: List<InstallationTaskProgress> = emptyList(),
    ) = InitializationProgress(InitializationStep.CREATE_CONTAINER, message, percent, fraction, tasks)

    private fun ensureMergedUsrLayout(
        onProgress: ((InitializationProgress) -> Unit)? = null,
    ) {
        onProgress?.invoke(createContainerProgress(
            "正在检查 Ubuntu 系统加载器",
            InitializationProgressPlan.LOADER_CHECK,
        ))
        val moved = MergedUsrLayout.repair(paths.rootfsDir)
        if (moved > 0) appendLog("MERGED_USR repaired entries=$moved")
        val loader = File(paths.rootfsDir, "lib/ld-linux-aarch64.so.1")
        check(loader.isFile) { "Ubuntu 动态加载器仍不可用：/lib/ld-linux-aarch64.so.1" }
        val dpkgDeb = File(paths.rootfsDir, "usr/bin/dpkg-deb")
        check(dpkgDeb.isFile) { "Ubuntu 离线安装器缺失：/usr/bin/dpkg-deb" }
    }

    private fun writeContainerFiles() {
        ensureNetworkConfig()
        installChineseTranslationPolicy()
        File(paths.rootfsDir, "etc/hostname").writeText("ubuntu\n")
        val hosts = File(paths.rootfsDir, "etc/hosts")
        val hostnameLine = "127.0.1.1 ubuntu"
        if (!hosts.readText().lineSequence().any { it.trim() == hostnameLine }) hosts.appendText("$hostnameLine\n")
    }

    private fun userCreationScript(username: String, password: CharArray): String {
        val credentials = "$username:${password.concatToString()}"
        return "id -u ${shell(username)} >/dev/null 2>&1 || useradd -m -s /bin/bash ${shell(username)}; " +
            "mkdir -p /home/${shell(username)}; printf '%s\\n' ${shell(credentials)} | chpasswd"
    }

    private fun environmentScript(username: String): String =
        "printf '%s\\n' 'export LANG=zh_CN.UTF-8' 'export LC_ALL=zh_CN.UTF-8' 'export LANGUAGE=zh_CN:zh' 'export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/usr/games' 'export LD_PRELOAD=${GetifaddrsBridge.CONTAINER_LIBRARY}' > /etc/profile.d/doro.sh && " +
            "ln -snf /usr/share/zoneinfo/Asia/Shanghai /etc/localtime && " +
            "printf '%s\\n' 'Asia/Shanghai' > /etc/timezone && " +
            "touch /home/${shell(username)}/.bashrc && " +
            "grep -qF 'export PS1=\\u@ubuntu:\\w\\$ ' /home/${shell(username)}/.bashrc || " +
            "printf '%s\\n' 'export PS1=\"\\[\\033[01;32m\\]\\u@\\h\\[\\033[00m\\]:\\[\\033[01;34m\\]\\w\\[\\033[00m\\]\\$ \"' >> /home/${shell(username)}/.bashrc; " +
            "chown -R ${shell(username)}:${shell(username)} /home/${shell(username)}"

    fun loadInitializationState(): InitializationState? {
        if (!paths.initializationStateFile.isFile) return null
        return runCatching {
            val values = Properties().apply { paths.initializationStateFile.inputStream().use(::load) }
            InitializationState(
                username = values.getProperty("username"),
                components = values.getProperty("components", "").split(',').filter(String::isNotBlank)
                    .mapNotNull { runCatching { InstallComponent.valueOf(it) }.getOrNull() }.toSet(),
                gpuProfile = GpuProfile.fromId(values.getProperty("gpuProfile")),
                completedStep = values.getProperty("completedStep")?.takeIf(String::isNotBlank)
                    ?.let { InitializationStep.valueOf(it) },
                failedMessage = values.getProperty("failedMessage")?.takeIf(String::isNotBlank)
            )
        }.getOrNull()
    }

    private fun saveInitializationState(state: InitializationState) {
        paths.initializationStateFile.parentFile?.mkdirs()
        val values = Properties().apply {
            setProperty("username", state.username)
            setProperty("components", state.components.joinToString(",") { it.name })
            setProperty("gpuProfile", state.gpuProfile.id)
            setProperty("completedStep", state.completedStep?.name.orEmpty())
            setProperty("failedMessage", state.failedMessage.orEmpty())
        }
        val temporary = File(paths.initializationStateFile.path + ".tmp")
        temporary.outputStream().use { values.store(it, "Doro initialization checkpoint") }
        check(temporary.renameTo(paths.initializationStateFile)) { "初始化检查点保存失败" }
    }

    private fun prepareLog() {
        paths.initializationLogFile.parentFile?.mkdirs()
        if (paths.initializationLogFile.length() > MAX_LOG_BYTES) {
            File(paths.initializationLogFile.path + ".old").also { old ->
                old.delete()
                paths.initializationLogFile.renameTo(old)
            }
        }
    }

    @Synchronized
    private fun appendLog(message: String) {
        prepareLog()
        paths.initializationLogFile.appendText("${System.currentTimeMillis()} $message\n")
    }

    fun initializationLogPath(): String = paths.initializationLogFile.path

    private fun saveConfig(config: ContainerConfig) {
        paths.baseDir.mkdirs()
        val values = Properties().apply {
            setProperty("distribution", config.distribution)
            setProperty("version", config.version)
            setProperty("username", config.username)
            setProperty("gpuProfile", config.gpuProfile.id)
            setProperty("initialized", config.initialized.toString())
        }
        paths.configFile.outputStream().use { values.store(it, "Doro Linux container") }
    }

    private fun progress(step: InitializationStep, message: String) = InitializationProgress(step, message)

    private fun shell(value: String) = "'${value.replace("'", "'\\''")}'"

    companion object {
        private val bridgeLock = Any()
        private val runtimeInstallLock = Any()
        private val startupMaintenanceLock = Any()
        private val commonRuntimeLock = Any()
        private val installerAssetsLock = Any()
        private val sudoInstallLock = Any()
        private val trustedCertificatesLock = Any()
        private val desktopProfileLock = Any()
        private val vendorRuntimeRepairLock = Any()
        @Volatile private var bridgeProcess: Process? = null
        @Volatile private var runtimePreparedRevision: String? = null
        @Volatile private var startupMaintenanceRevision: String? = null
        private const val ROOTFS_ASSET = "rootfs/ubuntu-base-24.04.4-base-arm64.tar"
        private const val XKB_ASSET = "x11/xkb-data.tar"
        private const val ROOTFS_SHA256 = "f92e577b04b0d751aaf44c6e40bd772aa834ce2b7bf7b695799f44fecf943d46"
        private const val XKB_SHA256 = "02dde5c8c2e7b234c6900f7ed645c993cd0f8b78cb63ba500337bd9cc27de257"
        private const val GNOME_ASSET = "offline/gnome-24.04-arm64.tar"
        private const val GNOME_ASSET_SIZE = 532357120L
        private const val GNOME_SHA256 =
            "62c4d9e6a1560faf0e0adaa00c7780cde9bb14ce42909811adfbf1444edd6ba4"
        private const val GNOME_PACKAGE_LIST_ASSET = "offline/gnome-packages.txt"
        private const val GNOME_PACKAGE_LIST = "gnome-packages.txt"
        private const val GNOME_MARKER_PREFIX = "ubuntu-24.04-gnome-46-"
        private const val GNOME_MARKER_VERSION = "${GNOME_MARKER_PREFIX}r3"
        private const val YARU_THEME_ICON_PACKAGE_FILE =
            "yaru-theme-icon_24.04.2-0ubuntu1_all.deb"
        private const val COMMON_LIBATOMIC_FILE =
            "libatomic1_14.2.0-4ubuntu2~24.04.1_arm64.deb"
        private const val COMMON_LIBATOMIC_ASSET = "offline/common/$COMMON_LIBATOMIC_FILE"
        private const val COMMON_LIBATOMIC_SHA256 =
            "fdeab74e7ad8572cf69c7024e8040c7ac851f6e667d35f0d429e7a0352840f73"
        private const val COMMON_RUNTIME_MARKER_VERSION = "libatomic1-14.2.0-4ubuntu2~24.04.1"
        private const val SUDO_PACKAGE_FILE = "sudo_1.9.15p5-3ubuntu5.24.04.4_arm64.deb"
        private const val SUDO_PACKAGE_ASSET = "offline/sudo/$SUDO_PACKAGE_FILE"
        private const val SUDO_PACKAGE_SHA256 =
            "e42a9bce73cbe70b287661738f4bcc365cad1243e8d127412ba751471c928d3d"
        private const val SUDO_ACCESS_MARKER_VERSION = "standard-setuid-v1"
        private const val BOOTSTRAP_ASSET = "offline/bootstrap-arm64.tar"
        private const val BOOTSTRAP_ASSET_SIZE = 349440000L
        private const val BOOTSTRAP_SHA256 =
            "7418eba8bd67c1f2062d53b823ab82d6b88766e1e95f76a7b2412f3ceace5152"
        private val BOOTSTRAP_MARKER_VERSION = "bootstrap-$BOOTSTRAP_SHA256"
        private const val BOOTSTRAP_PACKAGE_LIST_ASSET = "offline/bootstrap-packages.txt"
        private const val BOOTSTRAP_PACKAGE_LIST = "bootstrap-packages.txt"
        private const val VENDOR_APPS_ASSET = "offline/vendor-apps-arm64.tar"
        private const val VENDOR_APPS_ASSET_SIZE = 403352221L
        private const val VENDOR_PACKAGE_LIST_ASSET = "offline/vendor-packages.txt"
        private const val VENDOR_PACKAGE_LIST = "vendor-packages.txt"
        private const val VENDOR_APP_TASKS_FILE = "vendor-app-tasks.tsv"
        private const val VENDOR_DEPENDENCIES_FILE = "vendor-dependencies.txt"
        private const val VENDOR_DEPENDENCY_TASK_KEY = "dependencies"
        private const val VENDOR_REFRESH_TASK_KEY = "refresh"
        private const val VENDOR_VERIFY_TASK_KEY = "verify"
        private const val OFFLINE_PACKAGES_PATH = "/var/cache/doro-offline/packages"
        private const val VENDOR_APPS_SHA256 =
            "3c4146e38ccb799d76cc30b21e03a30a8f6cf5b5bdef9bcae5f1fd6eb09e4517"
        private const val DEB_INSTALLER_ASSETS_VERSION = "deb-installer-assets-v2"
        private const val USER_DIRECTORIES_VERSION = "linuxhub-user-directories-v1"
        private const val MESA_GPU_ASSET = "offline/mesa-for-android-container_27.0.0-devel-20261025-r2_ubuntu_noble_arm64.tar"
        private const val MESA_GPU_SHA256 = "6c456733fe9d3ef7a7a2d6c5519a42d55023f634842c16b48a8b9a817927f846"
        private const val MESA_GPU_ASSET_SIZE = 12172063L
        private const val DESKTOP_WALLPAPER_ASSET = "desktop/ubuntu-wallpaper.png"
        private const val WHITESUR_GTK_ASSET = "desktop/whitesur-gtk.tar"
        private const val WHITESUR_GTK_SHA256 = "8ef37e0b7d66b57c6c1e0920bc72d356768c8d17dad661deab214e5fce23102b"
        private const val WHITESUR_GTK_ASSET_SIZE = 402735L
        private const val TELA_CIRCLE_ASSET = "desktop/tela-circle.tar"
        private const val TELA_CIRCLE_SHA256 = "cb34fb564dab6c6e6e4cb2d61f5f7d79b22162da95bdce1adb0af1f6234a50b9"
        private const val TELA_CIRCLE_ASSET_SIZE = 3514547L
        private const val LINUXHUB_ICONS_ASSET = "desktop/linuxhub-icons.bin"
        private const val LINUXHUB_ICONS_SHA256 = "5bb9cae7ebf625eda49647a75759783eba642da725b0065e2daac84b98a9776d"
        private const val LINUXHUB_ICONS_ASSET_SIZE = 205175L
        private const val ZH_CN_TRANSLATIONS_ASSET = "desktop/zh_CN-translations.tar"
        private const val ZH_CN_TRANSLATIONS_SHA256 = "2f537c9e9653331c05148964b08c3d3f16b26de03e96d978da698dd4b80fe119"
        private const val ZH_CN_TRANSLATIONS_ASSET_SIZE = 839680L
        private const val FETCH_SCRIPT_ASSET = "desktop/doro-fetch.sh"
        private const val FASTFETCH_FILE = "fastfetch-linux-aarch64.deb"
        private const val FASTFETCH_ASSET = "offline/fastfetch/$FASTFETCH_FILE"
        private const val FASTFETCH_SHA256 = "e809ecce6f409dec4857a527f9406628d168313d9f6d33a87b0c19c0535d78b0"
        private const val LIBRSVG_COMMON_FILE = "librsvg2-common_2.58.0+dfsg-1ubuntu0.1_arm64.deb"
        private const val LIBRSVG_COMMON_ASSET = "offline/librsvg/$LIBRSVG_COMMON_FILE"
        private const val LIBRSVG_COMMON_SHA256 = "16d24385bb6a1a32f9a44b28ba8b2ce8b0557954d7b9187a8d54391c8ef98cbe"
        private const val OPENSSL_FILE = "openssl_3.0.13-0ubuntu3.16_arm64.deb"
        private const val OPENSSL_ASSET = "offline/certificates/$OPENSSL_FILE"
        private const val OPENSSL_SHA256 = "07fa666e2dfe758e5c4e19ccc695a19e8a56fb512fa55bd7de89219a1e65d372"
        private const val CA_CERTIFICATES_FILE = "ca-certificates_20260601~24.04.1_all.deb"
        private const val CA_CERTIFICATES_ASSET = "offline/certificates/$CA_CERTIFICATES_FILE"
        private const val CA_CERTIFICATES_SHA256 = "6bac2a01979e210d9eac1d4d56747ec709ea60654744d66705dc3c36e7629e50"
        private const val COMMAND_TIMEOUT_MS = 30L * 60L * 1000L
        private const val COMMAND_IDLE_TIMEOUT_MS = 5L * 60L * 1000L
        private const val OFFLINE_INSTALL_TIMEOUT_MS = 90L * 60L * 1000L
        private const val VENDOR_APP_TIMEOUT_MS = 15L * 60L * 1000L
        private const val VENDOR_APP_IDLE_TIMEOUT_MS = 3L * 60L * 1000L
        // Slow phones can legitimately stop producing dpkg output for several
        // minutes while unpacking or configuring. Keep a hard watchdog, but do
        // not abort a healthy install after only two minutes of silence.
        private const val OFFLINE_INSTALL_IDLE_TIMEOUT_MS = 10L * 60L * 1000L
        private const val WATCHDOG_POLL_MS = 1_000L
        private const val PROCESS_KILL_GRACE_MS = 5_000L
        private const val MAX_LOG_BYTES = 5L * 1024L * 1024L
        private const val MAX_CAPTURED_OUTPUT_CHARS = 256 * 1024
    }
}
