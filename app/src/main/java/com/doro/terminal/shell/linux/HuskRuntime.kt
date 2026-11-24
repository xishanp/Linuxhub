package com.doro.terminal.shell.linux

import android.system.Os
import android.system.OsConstants
import java.io.File
import java.security.MessageDigest

/**
 * The Husk runtime: our own loader + path shim (see the Husk repository) used in
 * place of `libproot.so`.
 *
 * How the guest contract differs from a proot container, in one place:
 *
 *  - `-r <rootfs>` becomes `NR_ROOTFS`; `PROOT_LOADER` becomes `NR_LD`. Both must
 *    be re-stated after every environment reset (`env -i`, `su`, `login`),
 *    because the shim finds the guest root *only* through `NR_ROOTFS`. Missing
 *    either one looks like "the shim stopped working": the parent resolves guest
 *    paths, the child falls back to the host.
 *  - `-0` + `su - <user>` becomes `NR_FAKE_UID`/`NR_FAKE_GID`: the shim reports
 *    the guest identity, so a login shell as a normal user needs no `su` at all
 *    (and `su` is in fact broken here: libpam returns PAM_SYSTEM_ERR, see the
 *    Husk repo's evidence 129).
 *  - `-b /dev -b /proc -b /sys` and the compat-file binds are gone: the shim
 *    synthesises those itself.
 *  - `--link2symlink` is gone: the shim's link() falls back to a copy.
 *  - guest-only share directories (`/run/doro-audio`, `/run/doro-desktop`,
 *    `/tmp/.X11-unix`) are made real *inside the rootfs* instead of being
 *    bound from the app's private dir. Both sides then agree on one path.
 *
 * The loader takes the program name as a *relative* path (`usr/bin/env`), so the
 * process has to start with the rootfs as its working directory.
 */
class HuskRuntime(private val rootfsDir: File, private val baseDir: File) {

    companion object {
        const val LOADER_ASSET = "husk/arm64-v8a/libld.so"
        const val USERMGR_ASSET = "husk/arm64-v8a/husk-usermgr"
        const val LIBC_ASSET = "husk/arm64-v8a/libc.so.6"
        const val LDCONFIG_ASSET = "husk/arm64-v8a/husk-ldconfig"
        const val SU_ASSET = "husk/arm64-v8a/husk-su"
        private const val ASSET_REVISION = "husk-runtime-spawn-link-17"
        private const val LIBC_BASE_SHA256 = "fe5966a43e068ad7cb389c3affa069f4ee6f296e07d7ccc0398a23cfde4f0b7e"
        private const val LIBC_PATCHED_SHA256 = "32d4d3f9501a2c717cf2246a2df1a898f1361b49e0576614461d70fc94774b56"
        private val shadowTools =
            listOf("useradd", "groupadd", "usermod", "userdel", "groupdel", "chpasswd", "passwd", "chfn", "chsh")
        private val shadowToolDirs = listOf("usr/sbin", "usr/bin")
    }

    /** Extracted from the APK on first use. Absent file = no usable runtime. */
    val loader = File(baseDir, "husk/libld.so")

    /** Patched glibc used by the guest; shipped beside the loader. */
    val libc = File(rootfsDir, "usr/lib/aarch64-linux-gnu/libc.so.6")

    /** Kernel seccomp traps are recorded here by the embedded shim. */
    val sigsysLog = File(rootfsDir, "tmp/nr-sigsys.log")

    /** Guest-side directories a 2.0 session expects to exist. */
    // `/dev/shm` is POSIX shared memory: Android has no such directory, and the
    // shim maps the name into the guest root on purpose (see path-shim's
    // passthrough rules). proot used to bind the app's own tmp/shm there, so the
    // directory has to exist now that the bind is gone - without it every
    // shm_open() in the guest is ENOENT (evidence 146 §3).
    private val guestDirs = listOf("run", "run/doro-audio", "run/doro-desktop", "tmp", "tmp/.X11-unix", "dev/shm")

    /** The two directories Ubuntus's own loader searches by default. */
    private val libraryDirs = listOf(
        "lib/aarch64-linux-gnu",
        "usr/lib/aarch64-linux-gnu",
    )

    /**
     * Private library directories, used only when the guest has not told us its
     * own list yet.
     *
     * These four were added one at a time while chasing GNOME failures under
     * proot; they are exactly what `DT_RUNPATH` is supposed to provide, except
     * that glibc resolves RUNPATH inside the loader where nothing can interpose
     * (Husk evidence 139 §3.1). `husk-ldconfig --library-path-file` now reads
     * every RUNPATH the guest declares and writes it to [huskLibraryPathFile];
     * this list stays as a fallback for a rootfs where that has not run yet.
     */
    private val fallbackPrivateLibraryDirs = listOf(
        "usr/lib/aarch64-linux-gnu/systemd",
        "usr/lib/aarch64-linux-gnu/mutter-14",
        "usr/lib/gnome-shell",
        "usr/lib/aarch64-linux-gnu/gvfs",
    )

    /** Written by `husk-ldconfig --library-path-file`: guest-absolute dirs, one per line. */
    val huskLibraryPathFile = File(rootfsDir, "etc/ld.so.husk-path")

    val isPresent: Boolean
        get() = loader.isFile && loader.canExecute() && rootfsDir.isDirectory

    /**
     * Put the runtime where the session can execute it, and give the guest its
     * own account tools. Ubuntu's shadow tools cannot work in this SELinux
     * domain (`lckpwdf()` needs `link(2)`, which is refused; measured in the
     * Husk repo, evidence 111), so `useradd`-family names point at husk-usermgr.
     */
    fun ensureInstalled(copyAsset: (String, File) -> Unit) {
        synchronized(this) {
            val marker = File(baseDir, "husk/assets.version")
            val refresh = !marker.isFile || marker.readText().trim() != ASSET_REVISION
            if (refresh || !loader.isFile) {
                loader.parentFile?.mkdirs()
                copyAsset(LOADER_ASSET, loader)
                loader.setExecutable(true, false)
            }
            val sbin = File(rootfsDir, "usr/sbin")
            if (!sbin.isDirectory) return
            val tool = File(sbin, "husk-usermgr")
            if (refresh || !tool.isFile) {
                copyAsset(USERMGR_ASSET, tool)
                tool.setExecutable(true, false)
            }
            // The guest libc must carry two build-time fixes that cannot be
            // done by GOT interposition: clone3 returns ENOSYS so glibc takes
            // its legacy clone path, and libc-internal path calls go through
            // HUSK-SLOT-1 entries. Only replace the known Ubuntu 24.04 base or
            // our own previously shipped file; never clobber an unknown distro.
            val libcHash = sha256(libc)
            if (libcHash == null || libcHash == LIBC_BASE_SHA256 || libcHash == LIBC_PATCHED_SHA256) {
                if (libcHash != LIBC_PATCHED_SHA256) {
                    copyAsset(LIBC_ASSET, libc)
                    libc.setExecutable(true, false)
                }
            }
            // /usr/sbin/ldconfig must never reach the distro's ldconfig.real:
            // it is *static*, so no GOT interposition can reach it, and this
            // device's untrusted_app seccomp policy answers its startup syscalls
            // with SIGSYS. Measured: the 2.0 initialisation, run from the UI on a
            // freshly unpacked Ubuntu base, died with "Bad system call" in the
            // very first guest command it issued (Husk evidence 137).
            val ldconfig = File(rootfsDir, "usr/sbin/husk-ldconfig")
            if (refresh || !ldconfig.isFile) {
                copyAsset(LDCONFIG_ASSET, ldconfig)
                ldconfig.setExecutable(true, false)
            }
            // `su` must be ours as well. util-linux' su is dynamically linked,
            // so it does reach the shim, but it cannot change identity here: its
            // identity change runs `initgroups()` inside libc, whose setgroups()
            // never passes the GOT (measured: "runuser: cannot set groups:
            // Operation not permitted"), and its PAM stack answers
            // "su: System error". LinuxHub calls `su - <user> -c ...` in three
            // places while initialising, so this is on the init path.
            val huskSu = File(sbin, "husk-su")
            if (refresh || !huskSu.isFile) {
                copyAsset(SU_ASSET, huskSu)
                huskSu.setExecutable(true, false)
            }
            installSuWrappers()
            installLdconfigWrapper()
            installShadowTools()
            if (refresh) {
                marker.parentFile?.mkdirs()
                marker.writeText("$ASSET_REVISION\n")
            }
        }
    }

    /**
     * Point every `ldconfig` the guest can reach at our Rust writer.
     *
     * Ubuntu ships `/usr/sbin/ldconfig` as a shell wrapper around
     * `/sbin/ldconfig.real`, a statically linked glibc binary; static code never
     * passes through the shim, so in this domain it either writes the *host*
     * /etc (EROFS) or dies on a trapped syscall. The distro copy is kept as
     * `ldconfig.ubuntu` for reference and never executed.
     */
    private fun installLdconfigWrapper() {
        val wrapper = "#!/bin/sh\nexec /usr/sbin/husk-ldconfig \"\$@\"\n"
        val dirs = listOf("usr/sbin", "sbin")
            .map { File(rootfsDir, it) }
            .filter { it.isDirectory }
            // merged-/usr makes /sbin and /usr/sbin the same directory
            .distinctBy { runCatching { it.canonicalPath }.getOrDefault(it.path) }
        dirs.forEach { dir ->
            val link = File(dir, "ldconfig")
            if (!link.exists() && !isSymlink(link)) return@forEach
            val stock = File(dir, "ldconfig.ubuntu")
            if (!isSymlink(link) && !stock.exists() && !isHuskLdconfigWrapper(link)) {
                link.renameTo(stock)
            }
            if (link.exists() || isSymlink(link)) link.delete()
            runCatching {
                link.writeText(wrapper)
                link.setExecutable(true, false)
            }
        }
    }

    /**
     * Point `su` and `runuser` at husk-su.
     *
     * Inside the container the identity is a fiction the shim maintains
     * (`NR_FAKE_UID`/`NR_FAKE_GID`); husk-su picks the target account, sets
     * those contract variables and execs the shell, which needs no privilege at
     * all. The distro originals are kept as `su.util-linux` / `runuser.util-linux`
     * so the diversion is visible and reversible.
     */
    private fun installSuWrappers() {
        listOf("usr/bin/su", "sbin/su", "usr/sbin/runuser", "sbin/runuser")
            .map { File(rootfsDir, it) }
            .filter { it.exists() || isSymlink(it) }
            // merged-/usr makes /bin and /usr/bin the same directory
            .distinctBy { runCatching { it.canonicalPath }.getOrDefault(it.path) }
            .forEach { link ->
                if (!isSymlink(link) && !isHuskSuWrapper(link)) {
                    val stock = File(link.parentFile, link.name + ".util-linux")
                    if (!stock.exists()) link.renameTo(stock)
                }
                if (link.exists() || isSymlink(link)) link.delete()
                runCatching {
                    link.writeText("#!/bin/sh\nexec /usr/sbin/husk-su \"\$@\"\n")
                    link.setExecutable(true, false)
                }
            }
    }

    private fun isHuskSuWrapper(file: File): Boolean =
        runCatching { file.length() < 512 && file.readText().contains("husk-su") }.getOrDefault(false)

    private fun isHuskLdconfigWrapper(file: File): Boolean =
        runCatching { file.length() < 512 && file.readText().contains("husk-ldconfig") }.getOrDefault(false)

    /**
     * The loader resolves a symlink to its target before exec, so argv[0] becomes
     * `husk-usermgr` and the tool cannot tell whether it was called as useradd or
     * groupadd. A tiny wrapper keeps the invocation name explicit. This mirrors
     * tools/guest-config/install-usermgr.sh in the Husk repository.
     */
    private fun installShadowTools() {
        shadowToolDirs.forEach { dir ->
            val directory = File(rootfsDir, dir)
            if (!directory.isDirectory) return@forEach
            shadowTools.forEach { name ->
                val link = File(directory, name)
                if (!link.exists() && !isSymlink(link)) return@forEach
                if (link.exists() && !isSymlink(link) && !isHuskWrapper(link)) {
                    val real = File(directory, "$name.shadow")
                    if (!real.exists() && !link.renameTo(real)) return@forEach
                }
                if (link.exists() || isSymlink(link)) link.delete()
                runCatching {
                    link.writeText("#!/bin/sh\nexec /usr/sbin/husk-usermgr $name \"$@\"\n")
                    link.setExecutable(true, false)
                }
            }
        }
    }

    private fun isHuskWrapper(file: File): Boolean =
        runCatching { file.length() < 512 && file.readText().contains("husk-usermgr") }.getOrDefault(false)

    private fun isSymlink(file: File): Boolean = runCatching {
        (Os.lstat(file.path).st_mode and OsConstants.S_IFMT) == OsConstants.S_IFLNK
    }.getOrDefault(false)

    private fun sha256(file: File): String? = runCatching {
        if (!file.isFile) return@runCatching null
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        digest.digest().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }.getOrNull()

    fun prepare() {
        guestDirs.forEach { File(rootfsDir, it).mkdirs() }
    }

    private fun libraryPath(): String {
        // Keep every known guest library directory in the search path. Missing
        // entries are harmless to the loader; filtering through File.isDirectory
        // here has already hidden a late-unpacked systemd private directory from
        // resumed package configuration.
        val declared = declaredLibraryDirs()
        val known = libraryDirs.map { "${rootfsDir.path}/$it" } +
            fallbackPrivateLibraryDirs.map { "${rootfsDir.path}/$it" }
        // Declared entries are guest-absolute, so they only need the rootfs prefix.
        val fromGuest = declared.map { rootfsDir.path + it }
        return (known + fromGuest).distinct().joinToString(":")
    }

    /** The guest's own RUNPATH directories, as recorded by husk-ldconfig. */
    private fun declaredLibraryDirs(): List<String> = runCatching {
        if (!huskLibraryPathFile.isFile) return@runCatching emptyList()
        huskLibraryPathFile.readLines()
            .map { it.trim() }
            .filter { it.startsWith("/") && it.length > 1 }
            .map { it.trimEnd('/') }
    }.getOrDefault(emptyList())

    /**
     * Environment the loader and its nested execs need. These values must also
     * survive the guest-side `env -i`, so [command] repeats them in its argv.
     *
     * `--library-path` replaces the loader's default search path. The shim
     * gives the same complete value to every exec'd guest ELF; without this,
     * private directories such as `usr/lib/aarch64-linux-gnu/systemd` vanish
     * after the first process and package postinst scripts fail to start.
     */
    fun bootstrapEnvironment(): Map<String, String> = mapOf(
        "NR_ROOTFS" to rootfsDir.path,
        "NR_LD" to loader.path,
        "NR_LIBRARY_PATH" to libraryPath(),
        "NR_SIGSYS" to "enosys",
        "NR_SIGSYS_LOG" to sigsysLog.path,
        // glibc probes rseq inside the loader before _dl_init. Android's
        // untrusted_app policy traps that optional syscall, so glibc must take
        // its documented disabled-rseq path from the very first process.
        "GLIBC_TUNABLES" to "glibc.pthread.rseq=0",
    )

    /** `uid:gid:home:shell` for a guest user, read from the guest's own /etc/passwd. */
    private fun guestUser(name: String): Array<String>? {
        val passwd = File(rootfsDir, "etc/passwd")
        if (!passwd.isFile) return null
        val line = passwd.useLines { lines ->
            lines.firstOrNull { it.startsWith("$name:") }
        } ?: return null
        val fields = line.split(':')
        if (fields.size < 7) return null
        val home = fields[5].ifBlank { "/root" }
        val shell = fields[6].ifBlank { "/bin/bash" }
        return arrayOf(fields[2], fields[3], home, shell)
    }

    /**
     * A launchable argv. [user] null means the guest's root identity (which is
     * what a 2.0 desktop/session process runs as before it switches).
     */
    fun command(user: String?, command: List<String>): List<String> {
        // There is no proot fallback any more: a missing loader is a broken
        // install, and it must say so instead of failing inside exec().
        check(loader.isFile) { "Husk 运行时缺失：${loader.path}，请重新初始化 Ubuntu" }
        val identity = user?.let { guestUser(it) }
        val home = identity?.get(2) ?: "/root"
        val shell = identity?.get(3) ?: "/bin/bash"
        val name = user ?: "root"
        return buildList {
            add(loader.path)
            add("--library-path")
            add(libraryPath())
            add("usr/bin/env")
            add("-i")
            add("HOME=$home")
            add("USER=$name")
            add("LOGNAME=$name")
            add("SHELL=$shell")
            add("PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/usr/games")
            add("LANG=C.UTF-8")
            add("TERM=xterm-256color")
            add("COLORTERM=truecolor")
            // the two variables the shim cannot work without
            bootstrapEnvironment().forEach { (key, value) -> add("$key=$value") }
            if (identity != null) {
                add("NR_FAKE_UID=${identity[0]}")
                add("NR_FAKE_GID=${identity[1]}")
            }
            addAll(command)
        }
    }
}
