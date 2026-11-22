package com.doro.terminal.shell.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RemoteVideoPacketTest {
    @Test
    fun frameHeaderCarriesSequenceForAck() {
        val packet = RemoteVideoPacket(
            presentationTimeUs = 123_456L,
            sequence = 42L,
            keyFrame = true,
            data = byteArrayOf(1, 2, 3),
        )

        val message = ByteBuffer.wrap(packet.toMessage()).order(ByteOrder.BIG_ENDIAN)
        assertEquals(RemoteH264Encoder.MESSAGE_FRAME, message.get().toInt())
        assertEquals(123_456L, message.long)
        assertEquals(42, message.int)
        assertEquals(1, message.get().toInt())
        assertTrue(message.remaining() == 3)
    }
}
