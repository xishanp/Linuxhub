package com.doro.terminal.shell.desktop

import java.util.ArrayDeque

/**
 * Feedback controller for the low-latency H.264 stream.
 *
 * The browser acknowledges frame sequence numbers, so feedback still works when
 * the socket queue deliberately skips a GOP. Encoder timestamps and ACK
 * timestamps come from the same monotonic clock.
 */
internal class StreamFlowController(
    private val maxBitRate: Int,
    private val minBitRate: Int,
    fps: Int,
    private val enabled: Boolean = true,
    private val clockNs: () -> Long = System::nanoTime,
) {
    companion object {
        private const val LATENCY_BUDGET_NS = 100_000_000L
        private const val MAX_QUEUE_FRAMES = 16
        private const val BUFFER_CAP_RATIO = 0.35
        private const val BUFFER_CAP_MIN = 65_536L
        private const val BUFFER_CAP_MAX = 524_288L
        private const val FRAME_ESTIMATE_MULTIPLIER = 4L
        private const val MIN_FRAME_BYTES = 16_384L
        private const val DOWNGRADE_LATENCY_NS = 100_000_000L
        private const val DOWNGRADE_MIN_GAP_NS = 200_000_000L
        private const val DOWNGRADE_PERCENT = 85
        private const val UPGRADE_LATENCY_NS = 50_000_000L
        private const val UPGRADE_STABLE_NS = 5_000_000_000L
        private const val UPGRADE_STEP_DIVISOR = 16
        private const val UPGRADE_STEP_MIN = 50_000
        private const val THROUGHPUT_CAP_MULTIPLIER = 7.2
        private const val THROUGHPUT_WINDOW_NS = 200_000_000L
    }

    private data class QueuedFrame(
        val sequence: Long,
        val sizeBytes: Int,
        val encodedAtNs: Long,
        val keyFrame: Boolean,
    )

    private val pending = ArrayDeque<QueuedFrame>()
    private val frameIntervalNs = 1_000_000_000L / fps.coerceAtLeast(1)
    private val bufferCapBytes = ((maxBitRate / 8.0) * BUFFER_CAP_RATIO).toLong()
        .coerceIn(BUFFER_CAP_MIN, BUFFER_CAP_MAX)

    private var active = false
    private var nextSequence = 0L
    private var lastAcknowledgedSequence = 0L
    private var pendingBytes = 0L
    private var averageFrameBytes = maxOf(
        MIN_FRAME_BYTES,
        (maxBitRate / 8.0 / fps.coerceAtLeast(1)).toLong(),
    )
    private var currentBitRate = maxBitRate
    private var pendingBitRate: Int? = null
    private var lastDowngradeNs = 0L
    private var healthySinceNs = 0L
    private var waitingForKeyFrame = false
    private var dropDecisionActive = false
    private var lastLatencyNs = 0L
    private var throughputBytesPerSecond = 0.0
    private var throughputWindowStartedNs = 0L
    private var throughputWindowBytes = 0L
    private var droppedFrames = 0L
    private var downgradeCount = 0
    private var upgradeCount = 0

    @Synchronized
    fun setActive(active: Boolean) {
        if (this.active == active) return
        this.active = active
        pending.clear()
        pendingBytes = 0
        lastAcknowledgedSequence = nextSequence
        lastLatencyNs = 0
        healthySinceNs = 0
        waitingForKeyFrame = false
        dropDecisionActive = false
        throughputWindowStartedNs = 0
        throughputWindowBytes = 0
    }

    /** Returns the sequence number that must be carried by the encoded frame. */
    @Synchronized
    fun onFrameEncoded(sizeBytes: Int, keyFrame: Boolean): Long {
        val sequence = ++nextSequence
        if (!enabled || !active || sizeBytes <= 0) return sequence

        pendingBytes += sizeBytes
        averageFrameBytes = ((averageFrameBytes * 3) + sizeBytes) / 4
        pending.addLast(QueuedFrame(sequence, sizeBytes, clockNs(), keyFrame))

        if (keyFrame && waitingForKeyFrame) applyDowngrade(clockNs())
        return sequence
    }

    /** Browser feedback. Returns measured encode-to-ACK latency, or -1. */
    @Synchronized
    fun onClientAck(sequence: Long): Long {
        if (!enabled || !active || sequence <= lastAcknowledgedSequence) return -1
        if (sequence > nextSequence) return -1

        val nowNs = clockNs()
        var matched: QueuedFrame? = null
        while (true) {
            val frame = pending.peekFirst() ?: break
            if (frame.sequence > sequence) break
            pending.removeFirst()
            pendingBytes -= frame.sizeBytes
            matched = frame
        }
        if (matched == null) return -1

        lastAcknowledgedSequence = sequence
        lastLatencyNs = (nowNs - matched.encodedAtNs).coerceAtLeast(0L)
        onLatencySample(nowNs, lastLatencyNs, matched.keyFrame)
        return lastLatencyNs
    }

    private fun onLatencySample(nowNs: Long, latencyNs: Long, keyFrame: Boolean) {
        if (latencyNs > DOWNGRADE_LATENCY_NS) {
            healthySinceNs = 0
            if (!keyFrame) {
                waitingForKeyFrame = true
                return
            }
            applyDowngrade(nowNs)
            return
        }

        if (latencyNs >= UPGRADE_LATENCY_NS) {
            healthySinceNs = 0
            return
        }

        if (healthySinceNs == 0L) {
            healthySinceNs = nowNs
            return
        }
        val required = maxOf(UPGRADE_STABLE_NS, frameIntervalNs * 4)
        if (nowNs - healthySinceNs < required) return

        val step = maxOf(UPGRADE_STEP_MIN, (maxBitRate - minBitRate) / UPGRADE_STEP_DIVISOR)
        val next = minOf(maxBitRate, currentBitRate + step)
        if (next > currentBitRate) {
            currentBitRate = next
            pendingBitRate = next
            upgradeCount++
            healthySinceNs = nowNs
        }
    }

    private fun applyDowngrade(nowNs: Long) {
        if (nowNs - lastDowngradeNs < DOWNGRADE_MIN_GAP_NS) {
            waitingForKeyFrame = false
            return
        }

        val throughputCap = (throughputBytesPerSecond * 8 * THROUGHPUT_CAP_MULTIPLIER).toLong()
        var target = currentBitRate.toLong() * DOWNGRADE_PERCENT / 100
        if (throughputCap > 0) target = minOf(target, throughputCap)
        val next = target.coerceIn(minBitRate.toLong(), maxBitRate.toLong()).toInt()
        if (next < currentBitRate) {
            currentBitRate = next
            pendingBitRate = next
            lastDowngradeNs = nowNs
            downgradeCount++
        }
        waitingForKeyFrame = false
    }

    @Synchronized
    fun shouldDropInput(): Boolean {
        if (!enabled || !active || pending.isEmpty() || waitingForKeyFrame) {
            dropDecisionActive = false
            return false
        }

        val fps = 1e9 / frameIntervalNs
        val budgetBytes = ((frameIntervalNs + LATENCY_BUDGET_NS) / 1e9) * fps * averageFrameBytes
        val fourFrames = maxOf(MIN_FRAME_BYTES, averageFrameBytes * FRAME_ESTIMATE_MULTIPLIER)
        val threshold = minOf(bufferCapBytes, maxOf(fourFrames, budgetBytes.toLong()))
        val shouldDrop = pending.size >= MAX_QUEUE_FRAMES || pendingBytes > threshold
        if (shouldDrop && !dropDecisionActive) droppedFrames++
        dropDecisionActive = shouldDrop
        return shouldDrop
    }

    @Synchronized
    fun onFrameSent(sizeBytes: Int) {
        if (!enabled || sizeBytes <= 0) return
        val nowNs = clockNs()
        if (throughputWindowStartedNs == 0L) throughputWindowStartedNs = nowNs
        throughputWindowBytes += sizeBytes
        val elapsed = nowNs - throughputWindowStartedNs
        if (elapsed >= THROUGHPUT_WINDOW_NS) {
            throughputBytesPerSecond = throughputWindowBytes * 1e9 / elapsed
            throughputWindowStartedNs = nowNs
            throughputWindowBytes = 0
        }
    }

    @Synchronized
    fun consumeBitRateChange(): Int? = pendingBitRate.also { pendingBitRate = null }

    @Synchronized
    fun statusLine(): String = buildString {
        append("flow{queue=").append(pending.size)
        append(" backlog=").append(backlogNs() / 1_000_000).append("ms")
        append(" latency=").append(lastLatencyNs / 1_000_000).append("ms")
        append(" bitrate=").append(currentBitRate / 1000).append("kbps")
        append(" dropped=").append(droppedFrames)
        append(" down=").append(downgradeCount).append(" up=").append(upgradeCount)
        append('}')
    }

    private fun backlogNs(): Long = if (averageFrameBytes <= 0) {
        0L
    } else {
        pendingBytes / averageFrameBytes * frameIntervalNs
    }
}
