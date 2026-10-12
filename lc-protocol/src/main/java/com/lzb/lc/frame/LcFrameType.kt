package com.lzb.lc.frame

enum class LcFrameType(val code: Byte) {
    HANDSHAKE(0x01),
    HEARTBEAT(0x02),
    HEARTBEAT_ACK(0x03),
    DATA(0x04),
    DATA_ACK(0x05);

    companion object {
        fun fromCode(code: Byte): LcFrameType? = entries.firstOrNull { it.code == code }
    }
}