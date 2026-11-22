package com.doro.terminal.shell.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopSizePolicyTest {
    @Test
    fun phoneLandscapeDesktopIsUnchanged() {
        assertEquals(2400 to 1080, DesktopSizePolicy.fit(2400, 1080))
        assertEquals(1280 to 1024, DesktopSizePolicy.fit(1280, 1024))
        assertEquals(1920 to 1080, DesktopSizePolicy.fit(1920, 1080))
    }

    @Test
    fun largeTabletPanelIsScaledInsteadOfRejected() {
        val (width, height) = DesktopSizePolicy.fit(3840, 2512)
        assertEquals(2160, height)
        assertTrue(width <= DesktopSizePolicy.MAX_LONG_SIDE)
        assertTrue(width > 3000)
    }

    @Test
    fun uhdTabletPanelKeepsAspectRatio() {
        val (width, height) = DesktopSizePolicy.fit(3840, 2400)
        assertEquals(2160, height)
        // 3840/2400 = 1.6 aspect; 2160 * 1.6 = 3456
        assertEquals(3456, width)
    }

    @Test
    fun portraitRequestStaysPortraitAndWithinBudget() {
        val (width, height) = DesktopSizePolicy.fit(2512, 3840)
        assertEquals(2160, width)
        assertTrue(height <= DesktopSizePolicy.MAX_LONG_SIDE)
        assertTrue(height > width)
    }

    @Test
    fun dimensionsStayEvenForH264() {
        listOf(
            3840 to 2512,
            3200 to 2400,
            2960 to 1848,
            2512 to 3840,
            1081 to 1921,
        ).forEach { (width, height) ->
            val (fittedWidth, fittedHeight) = DesktopSizePolicy.fit(width, height)
            assertEquals(0, fittedWidth % 2)
            assertEquals(0, fittedHeight % 2)
            assertTrue(minOf(fittedWidth, fittedHeight) <= DesktopSizePolicy.MAX_SHORT_SIDE)
            assertTrue(maxOf(fittedWidth, fittedHeight) <= DesktopSizePolicy.MAX_LONG_SIDE)
        }
    }

    @Test
    fun tinyRequestsAreRaisedToTheMinimum() {
        val (width, height) = DesktopSizePolicy.fit(100, 80)
        assertEquals(DesktopSizePolicy.MIN_WIDTH, width)
        assertEquals(DesktopSizePolicy.MIN_HEIGHT, height)
    }
}
