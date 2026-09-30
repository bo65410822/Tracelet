package com.lzb.lc.frame

import java.util.zip.CRC32

internal object FrameProtocol {
    const val MAGIC: Int = 0xA55A
    const val VERSION: Byte = 1
    const val HEADER_SIZE: Int = 13   // magic2+ver1+type1+flags1+seq4+length4
    const val CRC_SIZE: Int = 4

    fun crc32(data: ByteArray, offset: Int, length: Int): Long {
        val crc32 = CRC32()
        crc32.update(data, offset, length)
        return crc32.value
    }
}

sealed interface ParseResult {
    data class Frame(val frame: ParsedFrame, val consumed: Int) : ParseResult
    data object NeedMore : ParseResult          // 半包
    data object BadFrame : ParseResult           // 坏帧
}

data class ParsedFrame(
    val frameType: LcFrameType,
    val seq: Int,
    val payload: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ParsedFrame

        if (seq != other.seq) return false
        if (frameType != other.frameType) return false
        if (!payload.contentEquals(other.payload)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = seq
        result = 31 * result + frameType.hashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }
}

