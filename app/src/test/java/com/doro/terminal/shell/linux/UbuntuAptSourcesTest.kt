package com.doro.terminal.shell.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UbuntuAptSourcesTest {
    @Test
    fun pinsEveryUbuntuSourceToTheNobleBaseline() {
        val sources = UbuntuAptSources.sourcesList().lines().filter(String::isNotBlank)

        assertEquals(4, sources.size)
        assertTrue(sources.all { it.startsWith("deb https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports/ noble") })
        assertTrue(sources.all { it.endsWith(" main restricted universe multiverse") })
        assertFalse(sources.any { "resolute" in it || "plucky" in it || "oracular" in it })
    }
}
