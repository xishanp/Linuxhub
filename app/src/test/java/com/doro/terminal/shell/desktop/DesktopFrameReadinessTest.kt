package com.doro.terminal.shell.desktop

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopFrameReadinessTest {
    @Test
    fun blackPreDesktopFrameIsNotReady() {
        val metrics = DesktopFrameReadiness.analyze(IntArray(4_096) { 0xff000000.toInt() })
        assertFalse(metrics.ready)
    }

    @Test
    fun isolatedCursorPixelsDoNotLookLikeADesktop() {
        val pixels = IntArray(4_096) { 0xff000000.toInt() }
        repeat(12) { pixels[it * 37] = 0xffffffff.toInt() }
        assertFalse(DesktopFrameReadiness.analyze(pixels).ready)
    }

    @Test
    fun paintedGnomeWallpaperIsReady() {
        val pixels = IntArray(4_096) { index ->
            if (index % 3 == 0) 0xff5b2046.toInt() else 0xff772953.toInt()
        }
        val metrics = DesktopFrameReadiness.analyze(pixels)
        assertTrue(metrics.ready)
        assertTrue(metrics.averageLuma > 30.0)
    }
}
