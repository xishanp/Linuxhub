package com.doro.terminal.shell.desktop

import kotlin.math.max

/**
 * Distinguishes a painted desktop frame from Lorie's black pre-GNOME surface.
 * The runtime samples a scaled frame so this check is cheap enough to poll.
 */
internal object DesktopFrameReadiness {
    data class Metrics(
        val sampledPixels: Int,
        val visiblePixels: Int,
        val averageLuma: Double,
    ) {
        val visiblePermille: Int
            get() = if (sampledPixels == 0) 0 else visiblePixels * 1_000 / sampledPixels

        val ready: Boolean
            get() = sampledPixels >= MIN_SAMPLES &&
                visiblePixels >= MIN_VISIBLE_PIXELS &&
                visiblePermille >= MIN_VISIBLE_PERMILLE &&
                averageLuma >= MIN_AVERAGE_LUMA
    }

    private const val MAX_SAMPLES = 4_096
    private const val MIN_SAMPLES = 256
    private const val MIN_VISIBLE_PIXELS = 8
    private const val MIN_VISIBLE_PERMILLE = 10
    private const val MIN_AVERAGE_LUMA = 3.5

    fun analyze(pixels: IntArray): Metrics {
        if (pixels.isEmpty()) return Metrics(0, 0, 0.0)
        val step = max(1, pixels.size / MAX_SAMPLES)
        var samples = 0
        var visible = 0
        var lumaTotal = 0.0
        var index = 0
        while (index < pixels.size) {
            val color = pixels[index]
            if (color ushr 24 != 0) {
                val red = color ushr 16 and 0xff
                val green = color ushr 8 and 0xff
                val blue = color and 0xff
                val highest = max(red, max(green, blue))
                val lowest = minOf(red, green, blue)
                val luma = 0.2126 * red + 0.7152 * green + 0.0722 * blue
                lumaTotal += luma
                if (luma >= 20.0 && (highest - lowest >= 4 || luma >= 32.0)) visible++
                samples++
            }
            index += step
        }
        return Metrics(
            sampledPixels = samples,
            visiblePixels = visible,
            averageLuma = if (samples == 0) 0.0 else lumaTotal / samples,
        )
    }
}
