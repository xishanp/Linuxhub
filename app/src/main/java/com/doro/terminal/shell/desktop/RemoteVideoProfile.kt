package com.doro.terminal.shell.desktop

/**
 * Keeps Xlorie, MediaCodec and the browser viewport on the same quality policy.
 * 1080p-class desktops run at 60 FPS; larger views trade frame rate for sharpness.
 */
internal data class RemoteVideoProfile(
    val frameRate: Int,
    val bitRate: Int,
) {
    val bitRateLabel: String
        get() = "${bitRate / 1_000_000} Mbps"
}

internal object RemoteVideoProfilePolicy {
    const val MAX_WIDTH = 2560
    const val MAX_HEIGHT = 1600
    const val DEFAULT_WIDTH = 1920
    const val DEFAULT_HEIGHT = 1080

    private const val FULL_HD_PIXELS = DEFAULT_WIDTH * DEFAULT_HEIGHT
    private const val MAX_FRAME_RATE = 60
    private const val HIGH_RES_FRAME_RATE = 30
    private const val FULL_HD_BIT_RATE = 16_000_000
    private const val HIGH_RES_BIT_RATE = 20_000_000

    fun forSize(width: Int, height: Int): RemoteVideoProfile {
        val pixels = width.toLong() * height.toLong()
        return if (pixels <= FULL_HD_PIXELS) {
            RemoteVideoProfile(MAX_FRAME_RATE, FULL_HD_BIT_RATE)
        } else {
            RemoteVideoProfile(HIGH_RES_FRAME_RATE, HIGH_RES_BIT_RATE)
        }
    }
}
