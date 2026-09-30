package com.lzb.lc.frame

internal class FrameDecoder(private val maxPayloadBytes: Int) {

    private var writePos: Int = 0
    private var buffer: ByteArray = ByteArray(0)

    /**
     * 【第1次 feed：收到 25 字节】= 1个完整帧(17) + 8字节(半帧)
     * 第1步：buffer 堆入 25 字节，writePos=25
     * 第2步：offset=0
     *   tryParseOne(0, 25) → 够一帧！切出帧A，offset=17
     *   tryParseOne(17, 8) → 只有8字节 < 17 → NeedMore，停
     *   这轮切出 [帧A]
     * 第3步：把 [17,25) 这 8 字节搬到开头，writePos=8
     *
     * 【第2次 feed：又收到 12 字节】= 补齐上次的半帧还多出3字节
     * 第1步：接在那 8 字节后面，writePos = 8+12 = 20
     * 第2步：offset=0
     *   tryParseOne(0, 20) → 够一帧！切出帧B（用了前17字节），offset=17
     *   tryParseOne(17, 3) → 3 < 17 → NeedMore，停
     *   这轮切出 [帧B]
     * 第3步：把 [17,20) 这 3 字节搬到开头，writePos=3
     */
    fun feed(data: ByteArray, len: Int): DecodeOutcome {
        if (len <= 0) return DecodeOutcome.Frames(emptyList())
        ensureCapacity(writePos + len)
        System.arraycopy(data, 0, buffer, writePos, len)
        writePos += len
        val frames = mutableListOf<ParsedFrame>()
        var offset = 0
        while (offset < writePos) {
            val result = tryParseOne(buffer, offset, writePos - offset, maxPayloadBytes)
            when (result) {
                is ParseResult.Frame -> {
                    frames.add(result.frame)
                    offset += result.consumed
                }

                ParseResult.NeedMore -> break
                ParseResult.BadFrame -> return DecodeOutcome.Corrupt(frames)
            }
        }
        if (offset > 0) {
            val remaining = writePos - offset
            System.arraycopy(buffer, offset, buffer, 0, remaining)  // 半帧搬到开头
            writePos = remaining
            offset = 0
        }
        return DecodeOutcome.Frames(frames)
    }

    private fun ensureCapacity(capacity: Int) {
        if (capacity <= buffer.size) return
        var newLen = if (buffer.isEmpty()) 4096 else buffer.size
        while (newLen < capacity) newLen *= 2
        val copyOf = buffer.copyOf(newLen)
        buffer = copyOf
    }

    fun reset() {
        writePos = 0
        buffer = ByteArray(0)
    }

    /**
     * 1. 读多字节大端，每个字节都要 and 0xFF（防 Byte 符号扩展）—— 解析端头号坑
     * 2. 判断顺序：
     *    可用字节 < HEADER_SIZE(13) → 半包
     *    校验 magic → 不符判坏帧
     *    读 length → <0 或 > maxPayloadBytes → 坏帧（先校验再分配内存）
     *    可用字节 < 13 + length + 4 → 半包
     *    重算 crc 比对 → 不符坏帧
     *    通过 → 返回(帧, 消费字节数 = 13+length+4)
     * 3. 无状态：不持有缓冲，只对传入的 buffer 判断
     * 4. 返回值要能表达三种结果：成功(帧+消费长度) / 半包(需更多) / 坏帧
     */
    private fun tryParseOne(
        buffer: ByteArray,
        offset: Int,
        available: Int,
        maxPayloadBytes: Int
    ): ParseResult {

        if (available < FrameProtocol.HEADER_SIZE) return ParseResult.NeedMore
        // magic
        if (buffer[offset] != (FrameProtocol.MAGIC shr 8).toByte()
            || buffer[offset + 1] != (FrameProtocol.MAGIC and 0xFF).toByte()
        ) return ParseResult.BadFrame
        // 从帧头读 payload 长度
        val payloadLen = readInt32(buffer, offset + 9)
        if (payloadLen < 0 || payloadLen > maxPayloadBytes) return ParseResult.BadFrame

        // 整帧到齐了吗
        val frameSize = FrameProtocol.HEADER_SIZE + payloadLen + FrameProtocol.CRC_SIZE
        if (available < frameSize) return ParseResult.NeedMore

        // crc 校验：覆盖 [offset, offset+HEADER_SIZE+payloadLen)
        val calc = FrameProtocol.crc32(buffer, offset, FrameProtocol.HEADER_SIZE + payloadLen)
        val stored = readInt32(
            buffer,
            offset + FrameProtocol.HEADER_SIZE + payloadLen
        ).toLong() and 0xFFFFFFFFL
        if (calc != stored) return ParseResult.BadFrame

        // 解析字段
        val type = LcFrameType.fromCode(buffer[offset + 3]) ?: return ParseResult.BadFrame

        val seq = readInt32(buffer, offset + 5)
        val payload = buffer.copyOfRange(offset + 13, offset + 13 + payloadLen)
        return ParseResult.Frame(ParsedFrame(type, seq, payload), frameSize)
    }

    private fun readInt32(b: ByteArray, p: Int): Int =
        ((b[p].toInt() and 0xFF) shl 24) or ((b[p + 1].toInt() and 0xFF) shl 16) or
                ((b[p + 2].toInt() and 0xFF) shl 8) or (b[p + 3].toInt() and 0xFF)
}

sealed interface DecodeOutcome {
    data class Frames(val frames: List<ParsedFrame>) : DecodeOutcome
    data class Corrupt(val frames: List<ParsedFrame>) : DecodeOutcome
}