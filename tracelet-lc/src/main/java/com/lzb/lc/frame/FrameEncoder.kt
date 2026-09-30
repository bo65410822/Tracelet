package com.lzb.lc.frame

/**
 * 协议格式
 * | magic | version | type | flags | seq  | length | payload   | crc32 |
 * |  2B   |   1B    | 1B   |  1B   |  4B  |  4B    | length B  |  4B   |
 */
internal class FrameEncoder {

    fun encode(frameType: LcFrameType, seq: Int, payload: ByteArray): ByteArray {
        // 按协议格式拼接字节数组
        val total = FrameProtocol.HEADER_SIZE + payload.size + FrameProtocol.CRC_SIZE
        val frame = ByteArray(total)
        frame[0] = (FrameProtocol.MAGIC shr 8).toByte()
        frame[1] = (FrameProtocol.MAGIC and 0xFF).toByte()
        frame[2] = FrameProtocol.VERSION
        frame[3] = frameType.code
        frame[4] = 0
        frame[5] = (seq shr 24).toByte()
        frame[6] = (seq shr 16).toByte()
        frame[7] = (seq shr 8).toByte()
        frame[8] = (seq and 0xFF).toByte()
        frame[9] = (payload.size shr 24).toByte()
        frame[10] = (payload.size shr 16).toByte()
        frame[11] = (payload.size shr 8).toByte()
        frame[12] = (payload.size and 0xFF).toByte()
        System.arraycopy(payload, 0, frame, 13, payload.size)
        val crc = FrameProtocol.crc32(frame, 0, total - FrameProtocol.CRC_SIZE).toInt()
        frame[total - 4] = (crc shr 24).toByte()
        frame[total - 3] = (crc shr 16).toByte()
        frame[total - 2] = (crc shr 8).toByte()
        frame[total - 1] = (crc and 0xFF).toByte()
        return frame
    }


}
