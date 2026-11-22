package com.doro.terminal.shell.desktop

import kotlin.math.roundToInt

/**
 * Keeps the X11 desktop inside the range the renderer and encoder can handle,
 * instead of rejecting the device's physical panel size outright.
 *
 * Tablets report panels such as 3840x2512. Launching GNOME used to fail with
 * "桌面尺寸超出范围" because the short side exceeded 2160. The desktop is a
 * scalable viewport anyway, so the physical size is scaled down here rather
 * than refused.
 */
internal object DesktopSizePolicy {
    const val MIN_WIDTH = 320
    const val MIN_HEIGHT = 240

    /** Short side cap, matching the H.264 level limit of 4096x2160 panels. */
    const val MAX_SHORT_SIDE = 2160

    /** Long side cap; keeps 4K-class panels addressable without going wider. */
    const val MAX_LONG_SIDE = 3840

    fun fit(width: Int, height: Int): Pair<Int, Int> {
        require(width > 0 && height > 0) { "桌面尺寸无效" }
        val shortSide = minOf(width, height)
        val longSide = maxOf(width, height)
        val scale = minOf(
            1.0,
            MAX_SHORT_SIDE.toDouble() / shortSide,
            MAX_LONG_SIDE.toDouble() / longSide,
        )
        val fittedWidth = evenSize(width * scale, MIN_WIDTH)
        val fittedHeight = evenSize(height * scale, MIN_HEIGHT)
        return fittedWidth to fittedHeight
    }

    /** H.264 requires even dimensions; keep both axes aligned. */
    private fun evenSize(value: Double, minimum: Int): Int {
        val rounded = value.roundToInt().coerceAtLeast(minimum)
        return if (rounded % 2 == 0) rounded else rounded - 1
    }
}
