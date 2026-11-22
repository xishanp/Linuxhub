package com.doro.terminal.shell.desktop

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AvcPacketizerTest {
    @Test
    fun buildsAvcDecoderConfigurationFromAnnexBCsd() {
        val sps = byteArrayOf(0x67, 0x42, 0x00, 0x1E, 0x11, 0x22)
        val pps = byteArrayOf(0x68, 0x01, 0x02)
        val csd = annexB(sps, pps)

        val config = AvcPacketizer.decoderConfiguration(ByteBuffer.wrap(csd), null)

        assertNotNull(config)
        config!!
        assertEquals(1, config[0].toInt())
        assertEquals(0x42, config[1].toInt() and 0xFF)
        assertEquals(0x1E, config[3].toInt() and 0xFF)
        assertEquals("avc1.42001E", AvcPacketizer.codecString(config))
        assertEquals(sps.size, ((config[6].toInt() and 0xFF) shl 8) or (config[7].toInt() and 0xFF))
        assertArrayEquals(sps, config.copyOfRange(8, 8 + sps.size))
    }

    @Test
    fun addsHighProfileConfigurationExtension() {
        val sps = byteArrayOf(0x67, 0x64, 0x00, 0x28, 0x11, 0x22)
        val pps = byteArrayOf(0x68, 0x01, 0x02)
        val config = AvcPacketizer.decoderConfiguration(
            ByteBuffer.wrap(annexB(sps, pps)),
            null,
        )!!

        assertEquals("avc1.640028", AvcPacketizer.codecString(config))
        assertArrayEquals(byteArrayOf(0xFD.toByte(), 0, 0, 0), config.takeLast(4).toByteArray())
    }

    @Test
    fun convertsAnnexBFramesToFourByteLengthPrefixes() {
        val first = byteArrayOf(0x65, 0x01, 0x02)
        val second = byteArrayOf(0x41, 0x03)
        val converted = AvcPacketizer.toLengthPrefixed(annexB(first, second))

        val view = ByteBuffer.wrap(converted).order(ByteOrder.BIG_ENDIAN)
        assertEquals(first.size, view.int)
        val firstOut = ByteArray(first.size).also(view::get)
        assertEquals(second.size, view.int)
        val secondOut = ByteArray(second.size).also(view::get)
        assertArrayEquals(first, firstOut)
        assertArrayEquals(second, secondOut)
        assertEquals(converted.size, view.position())
    }


    @Test
    fun buildsAvcDecoderConfigurationFromAvccCsd() {
        val sps = byteArrayOf(0x67, 0x42, 0x00, 0x1E, 0x11, 0x22)
        val pps = byteArrayOf(0x68, 0x01, 0x02)
        val csd = lengthPrefixed(sps, pps)

        val config = AvcPacketizer.decoderConfiguration(ByteBuffer.wrap(csd), null)

        assertNotNull(config)
        config!!
        assertEquals(0x42, config[1].toInt() and 0xFF)
        assertEquals("avc1.42001E", AvcPacketizer.codecString(config))
        assertArrayEquals(sps, config.copyOfRange(8, 8 + sps.size))
    }

    @Test
    fun keepsAlreadyLengthPrefixedFramesValid() {
        val first = byteArrayOf(0x65, 0x01, 0x02)
        val second = byteArrayOf(0x41, 0x03)
        val source = lengthPrefixed(first, second)
        val converted = AvcPacketizer.toLengthPrefixed(source)

        assertArrayEquals(source, converted)
    }

    private fun annexB(vararg units: ByteArray): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        units.forEach { unit ->
            output.write(byteArrayOf(0, 0, 0, 1))
            output.write(unit)
        }
        return output.toByteArray()
    }

    private fun lengthPrefixed(vararg units: ByteArray): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        units.forEach { unit ->
            output.write(
                byteArrayOf(
                    (unit.size ushr 24).toByte(),
                    (unit.size ushr 16).toByte(),
                    (unit.size ushr 8).toByte(),
                    unit.size.toByte(),
                ),
            )
            output.write(unit)
        }
        return output.toByteArray()
    }
}
