package com.doro.terminal.shell.desktop

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

internal data class RemoteVideoConfig(
    val width: Int,
    val height: Int,
    val avcC: ByteArray,
    val codec: String,
) {
    fun toMessage(): ByteArray {
        val buffer = ByteBuffer.allocate(9 + avcC.size)
        buffer.order(ByteOrder.BIG_ENDIAN)
        buffer.put(RemoteH264Encoder.MESSAGE_CONFIG.toByte())
        buffer.putInt(width)
        buffer.putInt(height)
        buffer.put(avcC)
        return buffer.array()
    }
}

internal data class RemoteVideoPacket(
    val presentationTimeUs: Long,
    val sequence: Long,
    val keyFrame: Boolean,
    val data: ByteArray,
) {
    fun toMessage(): ByteArray {
        val buffer = ByteBuffer.allocate(14 + data.size)
        buffer.order(ByteOrder.BIG_ENDIAN)
        buffer.put(RemoteH264Encoder.MESSAGE_FRAME.toByte())
        buffer.putLong(presentationTimeUs)
        buffer.putInt(sequence.toInt())
        buffer.put(if (keyFrame) 1 else 0.toByte())
        buffer.put(data)
        return buffer.array()
    }
}

/**
 * Hardware AVC encoder whose input Surface is rendered into directly by the
 * Xlorie EGL renderer. Encoded buffers contain only length-prefixed H.264 NAL
 * units; no Bitmap or PNG conversion happens on the frame path.
 */
internal class RemoteH264Encoder(
    private val width: Int,
    private val height: Int,
    private val bitRate: Int,
    private val frameRate: Int,
    private val onConfig: (RemoteVideoConfig) -> Unit,
    private val onPacket: (RemoteVideoPacket) -> Unit,
    private val onError: (String) -> Unit,
) {
    companion object {
        const val MESSAGE_CONFIG = 1
        const val MESSAGE_FRAME = 2

        private const val TAG = "LinuxHubH264"
        private const val MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val I_FRAME_INTERVAL_SECONDS = 1
        private const val DROP_INPUT_HOLD_NS = 120_000_000L
        private const val DROP_INPUT_COOLDOWN_NS = 180_000_000L
    }

    private val running = AtomicBoolean(false)
    private val flow = StreamFlowController(
        maxBitRate = bitRate,
        minBitRate = (bitRate / 8).coerceAtLeast(250_000),
        fps = frameRate,
    )
    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var outputThread: Thread? = null
    @Volatile private var publishedConfig: RemoteVideoConfig? = null
    @Volatile private var loggedFirstSample = false
    private var outputFrameCount = 0L
    private var outputFpsWindowAt = 0L
    private var lastFlowLogAt = 0L
    private var dropInputActive = false
    private var dropInputStartedAt = 0L
    private var nextDropPulseAt = 0L
    private var dropInputUnsupported = false

    fun start(): Surface {
        check(!running.get()) { "编码器已经启动" }
        val configured = try {
            createConfiguredCodec(includeLatencyHints = true)
        } catch (failure: Throwable) {
            Log.w(TAG, "low-latency encoder hints rejected: ${failure.message}; retrying without hints")
            createConfiguredCodec(includeLatencyHints = false)
        }
        val (created, surface) = configured
        codec = created
        inputSurface = surface
        running.set(true)
        outputThread = Thread({ drainOutput(created) }, "linuxhub-h264-output").apply {
            isDaemon = true
            start()
        }
        return surface
    }

    private fun createConfiguredCodec(includeLatencyHints: Boolean): Pair<MediaCodec, Surface> {
        val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_SECONDS)
            setInteger(
                MediaFormat.KEY_BITRATE_MODE,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR,
            )
            if (includeLatencyHints) {
                // Keep the codec in real-time mode. Without these hints some
                // vendor encoders buffer several frames before producing output.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    setInteger(MediaFormat.KEY_PRIORITY, 0)
                    setInteger(MediaFormat.KEY_OPERATING_RATE, frameRate)
                    setInteger(MediaFormat.KEY_LATENCY, 1)
                }
                // B-frames increase both encoder and decoder latency. A remote
                // desktop stream only needs the newest picture, not reordering.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                }
            }
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1)
            }
        }
        val created = MediaCodec.createEncoderByType(MIME_TYPE).also {
            Log.d(TAG, "using encoder=${it.name} lowLatencyHints=$includeLatencyHints")
        }
        try {
            created.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = created.createInputSurface()
            created.start()
            return created to surface
        } catch (failure: Throwable) {
            runCatching { created.stop() }
            runCatching { created.release() }
            throw failure
        }
    }

    fun requestKeyFrame() {
        if (!running.get()) return
        runCatching {
            codec?.setParameters(Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            })
        }.onFailure { onError("请求关键帧失败：${it.message}") }
    }

    fun onClientAck(sequence: Long) {
        flow.onClientAck(sequence)
    }

    fun onFrameSent(sizeBytes: Int) {
        flow.onFrameSent(sizeBytes)
    }

    fun setStreamingActive(active: Boolean) {
        flow.setActive(active)
        if (!active) setDropInputFrames(false)
    }

    fun statusLine(): String = flow.statusLine()

    fun await() {
        outputThread?.join()
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        outputThread?.interrupt()
        runCatching { outputThread?.join(700) }
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        runCatching { inputSurface?.release() }
        codec = null
        inputSurface = null
        outputThread = null
        publishedConfig = null
    }

    private fun publishConfig(avcC: ByteArray, source: String) {
        if (publishedConfig != null) return
        val config = RemoteVideoConfig(
            width = width,
            height = height,
            avcC = avcC,
            codec = AvcPacketizer.codecString(avcC),
        )
        publishedConfig = config
        Log.d(TAG, "config from $source: ${config.codec} ${width}x$height avcC=${avcC.size}")
        onConfig(config)
    }

    private fun publishConfigFromUnits(units: List<AvcPacketizer.NalUnit>, source: String) {
        if (publishedConfig != null) return
        AvcPacketizer.decoderConfiguration(units)?.let { publishConfig(it, source) }
    }

    private fun drainOutput(source: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        try {
            while (running.get()) {
                applyFlowPolicy()
                when (val index = source.dequeueOutputBuffer(info, 5_000L)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val format = source.outputFormat
                        val csd0 = format.getByteBuffer("csd-0")
                        val csd1 = format.getByteBuffer("csd-1")
                        Log.d(
                            TAG,
                            "format changed: csd0=${csd0?.remaining() ?: 0} csd1=${csd1?.remaining() ?: 0} format=$format",
                        )
                        val avcC = AvcPacketizer.decoderConfiguration(csd0, csd1)
                        if (avcC == null) {
                            Log.w(TAG, "format changed without usable SPS/PPS; waiting for codec config buffer")
                        } else {
                            publishConfig(avcC, "output-format")
                        }
                    }
                    else -> if (index >= 0) {
                        try {
                            val output = source.getOutputBuffer(index)
                            if (output != null && info.size > 0) {
                                output.position(info.offset)
                                output.limit(info.offset + info.size)
                                val sample = ByteArray(info.size)
                                output.get(sample)
                                if (!loggedFirstSample) {
                                    loggedFirstSample = true
                                    Log.d(
                                        TAG,
                                        "first sample size=${sample.size} flags=${info.flags} head=${sample.take(24).joinToString("") { "%02x".format(it) }}",
                                    )
                                }
                                // Some vendor codecs emit SPS/PPS as a codec-config
                                // output buffer instead of INFO_OUTPUT_FORMAT_CHANGED.
                                val units = AvcPacketizer.parseNalUnits(sample)
                                publishConfigFromUnits(units, "output-buffer")
                                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                    // SPS/PPS are delivered through avcC. Keeping a
                                    // duplicate in-band copy makes some browser
                                    // decoders produce green/blocked frames.
                                    val frameUnits = if (publishedConfig != null) {
                                        units.filter { it.type != 7 && it.type != 8 }
                                    } else {
                                        units
                                    }
                                    val prefixed = when {
                                        units.isEmpty() -> sample
                                        frameUnits.isEmpty() -> null
                                        else -> AvcPacketizer.toLengthPrefixed(frameUnits)
                                    }
                                    if (prefixed != null) {
                                        val keyFrame =
                                            info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                                        val sequence = flow.onFrameEncoded(prefixed.size, keyFrame)
                                        outputFrameCount++
                                        val now = SystemClock.elapsedRealtime()
                                        if (outputFpsWindowAt == 0L) outputFpsWindowAt = now
                                        if (now - outputFpsWindowAt >= 5_000L) {
                                            val fps = outputFrameCount * 1000.0 /
                                                (now - outputFpsWindowAt)
                                            Log.i(TAG, "encoded %.1f FPS (%dx%d)".format(fps, width, height))
                                            outputFrameCount = 0L
                                            outputFpsWindowAt = now
                                        }
                                        onPacket(
                                            RemoteVideoPacket(
                                                presentationTimeUs = info.presentationTimeUs,
                                                sequence = sequence,
                                                keyFrame = keyFrame,
                                                data = prefixed,
                                            ),
                                        )
                                    }
                                }
                            }
                        } finally {
                            source.releaseOutputBuffer(index, false)
                        }
                    }
                }
                applyPendingBitRate()
                logFlowStatus()
            }
        } catch (_: InterruptedException) {
            // Normal stop path.
        } catch (failure: Throwable) {
            if (running.get()) onError("H.264 编码失败：${failure.message}")
        } finally {
            running.set(false)
        }
    }

    private fun applyFlowPolicy() {
        val nowNs = System.nanoTime()
        val shouldDrop = flow.shouldDropInput()
        if (!shouldDrop) {
            if (dropInputActive) setDropInputFrames(false)
            return
        }
        if (dropInputActive && nowNs - dropInputStartedAt >= DROP_INPUT_HOLD_NS) {
            setDropInputFrames(false)
            nextDropPulseAt = nowNs + DROP_INPUT_COOLDOWN_NS
            return
        }
        if (!dropInputActive && nowNs >= nextDropPulseAt) {
            if (setDropInputFrames(true)) dropInputStartedAt = nowNs
        }
    }

    private fun setDropInputFrames(drop: Boolean): Boolean {
        if (drop == dropInputActive || dropInputUnsupported) return false
        if (!running.get()) return false
        val applied = runCatching {
            codec?.setParameters(Bundle().apply {
                putInt("drop-input-frames", if (drop) 1 else 0)
            })
        }.onFailure {
            dropInputUnsupported = true
            onError("编码器不支持 drop-input-frames：${it.message}")
        }.isSuccess && codec != null
        if (!applied) return false

        dropInputActive = drop
        if (!drop) requestKeyFrame()
        Log.i(TAG, if (drop) "drop-input-frames ON ${flow.statusLine()}" else "drop-input-frames OFF ${flow.statusLine()}")
        return true
    }

    private fun applyPendingBitRate() {
        val next = flow.consumeBitRateChange() ?: return
        val currentCodec = codec ?: return
        val applied = runCatching {
            currentCodec.setParameters(Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, next)
            })
        }.isSuccess
        if (applied) {
            requestKeyFrame()
            Log.i(TAG, "bitrate -> ${next / 1000}kbps ${flow.statusLine()}")
        } else {
            onError("动态调整码率失败：$next")
        }
    }

    private fun logFlowStatus() {
        val now = SystemClock.elapsedRealtime()
        if (lastFlowLogAt == 0L) lastFlowLogAt = now
        if (now - lastFlowLogAt < 5_000L) return
        lastFlowLogAt = now
        Log.i(TAG, flow.statusLine())
    }
}
