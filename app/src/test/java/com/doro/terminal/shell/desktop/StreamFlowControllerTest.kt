package com.doro.terminal.shell.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamFlowControllerTest {
    @Test
    fun acknowledgesFrameSequenceAndMeasuresLatency() {
        var now = 1_000_000_000L
        val flow = controller { now }
        flow.setActive(true)
        val sequence = flow.onFrameEncoded(24_000, keyFrame = false)

        now += 40_000_000L
        assertEquals(40_000_000L, flow.onClientAck(sequence))
        assertFalse(flow.shouldDropInput())
    }

    @Test
    fun fillsQueueWithoutAcknowledgementAndRequestsInputDrop() {
        var now = 1_000_000_000L
        val flow = controller { now }
        flow.setActive(true)
        repeat(15) {
            flow.onFrameEncoded(1, keyFrame = false)
            now += 33_000_000L
            assertFalse(flow.shouldDropInput())
        }
        flow.onFrameEncoded(1, keyFrame = false)

        assertTrue(flow.shouldDropInput())
    }

    @Test
    fun downgradesBitrateWhenAcknowledgedLatencyExceedsBudget() {
        var now = 1_000_000_000L
        val flow = controller { now }
        flow.setActive(true)
        val sequence = flow.onFrameEncoded(40_000, keyFrame = true)

        now += 400_000_000L
        flow.onClientAck(sequence)

        assertEquals(6_800_000, flow.consumeBitRateChange())
    }

    @Test
    fun holdsBitrateInsideDeadZone() {
        var now = 1_000_000_000L
        val flow = controller { now }
        flow.setActive(true)
        val sequence = flow.onFrameEncoded(40_000, keyFrame = true)

        now += 70_000_000L
        flow.onClientAck(sequence)

        assertNull(flow.consumeBitRateChange())
    }

    @Test
    fun upgradesAfterFiveHealthySeconds() {
        var now = 1_000_000_000L
        val flow = controller { now }
        flow.setActive(true)

        val slow = flow.onFrameEncoded(30_000, keyFrame = true)
        now += 400_000_000L
        flow.onClientAck(slow)
        assertEquals(6_800_000, flow.consumeBitRateChange())

        val first = flow.onFrameEncoded(30_000, keyFrame = false)
        now += 10_000_000L
        flow.onClientAck(first)

        now += 5_000_000_000L
        val second = flow.onFrameEncoded(30_000, keyFrame = false)
        now += 10_000_000L
        flow.onClientAck(second)

        assertEquals(7_237_500, flow.consumeBitRateChange())
    }

    private fun controller(clock: () -> Long) = StreamFlowController(
        maxBitRate = 8_000_000,
        minBitRate = 1_000_000,
        fps = 30,
        clockNs = clock,
    )
}
