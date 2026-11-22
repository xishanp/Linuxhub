package com.doro.terminal.shell.desktop

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Converts MediaCodec AVC output into the length-prefixed format expected by
 * WebCodecs when an AVCDecoderConfigurationRecord is supplied.
 */
internal object AvcPacketizer {
    data class NalUnit(val type: Int, val bytes: ByteArray)

    fun decoderConfiguration(csd0: ByteBuffer?, csd1: ByteBuffer?): ByteArray? {
        val units = buildList {
            csd0?.copyBytes()?.let { addAll(parseNalUnits(it)) }
            csd1?.copyBytes()?.let { addAll(parseNalUnits(it)) }
        }
        return decoderConfiguration(units)
    }

    fun decoderConfiguration(units: Collection<NalUnit>): ByteArray? {
        val sps = units.firstOrNull { it.type == 7 } ?: return null
        val pps = units.firstOrNull { it.type == 8 } ?: return null
        if (sps.bytes.size < 4 || pps.bytes.isEmpty()) return null

        return ByteArrayOutputStream().apply {
            write(1)
            write(sps.bytes[1].toInt())
            write(sps.bytes[2].toInt())
            write(sps.bytes[3].toInt())
            write(0xFF)
            write(0xE1)
            write(sps.bytes.size ushr 8)
            write(sps.bytes.size and 0xFF)
            write(sps.bytes)
            write(1)
            write(pps.bytes.size ushr 8)
            write(pps.bytes.size and 0xFF)
            write(pps.bytes)
            // AVCDecoderConfigurationRecord requires these fields for the
            // High profiles. MediaCodec on this device emits High profile
            // (0x64); omitting them makes some browser decoders reject the
            // description even though SPS/PPS themselves are valid.
            if (sps.bytes[1].toInt() and 0xFF in setOf(100, 110, 122, 144)) {
                write(0xFD) // reserved 111111 + chroma_format 1 (4:2:0)
                write(0)    // bit_depth_luma_minus8
                write(0)    // bit_depth_chroma_minus8
                write(0)    // numOfSequenceParameterSetExt
            }
        }.toByteArray()
    }

    fun codecString(config: ByteArray): String {
        require(config.size >= 4) { "avcC 配置过短" }
        return "avc1." + config.copyOfRange(1, 4).joinToString("") {
            (it.toInt() and 0xFF).toString(16).padStart(2, '0').uppercase()
        }
    }

    fun toLengthPrefixed(sample: ByteArray): ByteArray {
        val units = parseNalUnits(sample)
        if (units.isEmpty()) return sample
        return toLengthPrefixed(units)
    }

    fun toLengthPrefixed(units: Collection<NalUnit>): ByteArray {
        val output = ByteArrayOutputStream(units.sumOf { it.bytes.size } + units.size * 4)
        units.forEach { unit ->
            val size = unit.bytes.size
            output.write((size ushr 24) and 0xFF)
            output.write((size ushr 16) and 0xFF)
            output.write((size ushr 8) and 0xFF)
            output.write(size and 0xFF)
            output.write(unit.bytes)
        }
        return output.toByteArray()
    }

    /**
     * MediaCodec encoders are not consistent across vendors: some return
     * Annex-B csd buffers, others return AVCC length-prefixed buffers. Accept
     * both so a valid SPS/PPS pair is never lost just because the container
     * format differs.
     */
    fun parseNalUnits(bytes: ByteArray): List<NalUnit> {
        val annexB = parseAnnexB(bytes)
        if (annexB.isNotEmpty()) return annexB
        return parseLengthPrefixed(bytes)
    }

    private fun parseAnnexB(bytes: ByteArray): List<NalUnit> {
        val starts = mutableListOf<Pair<Int, Int>>()
        var index = 0
        while (index + 3 < bytes.size) {
            val startLength = when {
                bytes[index] == 0.toByte() && bytes[index + 1] == 0.toByte() &&
                    bytes[index + 2] == 1.toByte() -> 3
                index + 4 < bytes.size && bytes[index] == 0.toByte() &&
                    bytes[index + 1] == 0.toByte() && bytes[index + 2] == 0.toByte() &&
                    bytes[index + 3] == 1.toByte() -> 4
                else -> 0
            }
            if (startLength > 0) {
                starts += index to startLength
                index += startLength
            } else {
                index++
            }
        }
        if (starts.isEmpty()) return emptyList()

        return starts.mapIndexedNotNull { unitIndex, (start, headerSize) ->
            val payloadStart = start + headerSize
            val payloadEnd = starts.getOrNull(unitIndex + 1)?.first ?: bytes.size
            if (payloadEnd <= payloadStart) return@mapIndexedNotNull null
            val nal = bytes.copyOfRange(payloadStart, payloadEnd)
            if (nal.isEmpty()) return@mapIndexedNotNull null
            NalUnit(nal[0].toInt() and 0x1F, nal)
        }
    }

    private fun parseLengthPrefixed(bytes: ByteArray): List<NalUnit> {
        if (bytes.size < 5) return emptyList()
        val units = mutableListOf<NalUnit>()
        var index = 0
        while (index + 4 <= bytes.size) {
            val length = ((bytes[index].toInt() and 0xFF) shl 24) or
                ((bytes[index + 1].toInt() and 0xFF) shl 16) or
                ((bytes[index + 2].toInt() and 0xFF) shl 8) or
                (bytes[index + 3].toInt() and 0xFF)
            if (length <= 0 || length > bytes.size - index - 4) return emptyList()
            val start = index + 4
            val end = start + length
            val nal = bytes.copyOfRange(start, end)
            val type = nal[0].toInt() and 0x1F
            if (type == 0) return emptyList()
            units += NalUnit(type, nal)
            index = end
        }
        return if (index == bytes.size) units else emptyList()
    }

    private fun ByteBuffer.copyBytes(): ByteArray {
        val duplicate = duplicate().order(ByteOrder.LITTLE_ENDIAN)
        duplicate.position(0)
        val result = ByteArray(duplicate.remaining())
        duplicate.get(result)
        return result
    }
}
