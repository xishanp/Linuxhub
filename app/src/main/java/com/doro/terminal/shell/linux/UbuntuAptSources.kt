package com.doro.terminal.shell.linux

/** Ubuntu 24.04 APT source policy for the LinuxHub 2.0 ARM64 user space. */
internal object UbuntuAptSources {
    private const val MIRROR = "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports/"
    private const val SUITE = "noble"

    fun sourcesList(): String = buildString {
        listOf("", "-updates", "-security", "-backports").forEach { suffix ->
            append("deb $MIRROR $SUITE$suffix main restricted universe multiverse\n")
        }
    }
}
