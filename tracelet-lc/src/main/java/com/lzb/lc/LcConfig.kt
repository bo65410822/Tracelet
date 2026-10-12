package com.lzb.lc

data class LcConfig(
    val maxPayloadBytes: Long = 1 * 1024 * 1024,   // 默认 1MB,接入方按业务改
    val heartbeatIntervalMs: Long = 15_000,      // 默认 15秒
    val heartbeatTimeoutMs: Long = 45_000,      // 默认 45秒
    val connectTimeoutMs: Long = 10_000,        // 默认 10秒
    val backoffBaseMs: Long = 1_000,            // 默认 1秒
    val backoffMaxMs: Long = 30_000,            // 默认 30秒
    val maxReconnectAttempts: Int = -1,           // -1 = 不限
    val sendQueueCapacity: Int = 256,            // 默认 256，接入方按业务改

    val overflowPolicy: OverflowPolicy = OverflowPolicy.DROP_OLDEST,
    val sendPolicy: SendPolicy = SendPolicy.DROP_IF_UNSENDABLE
)
enum class OverflowPolicy { DROP_OLDEST, DROP_NEWEST }       // 队列满时
enum class SendPolicy { DROP_IF_UNSENDABLE, BUFFER_AND_RETRY } // 发不出去时
