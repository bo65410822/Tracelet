package com.lzb.core

/**
 * Tracelet SDK 用户级配置。
 *
 * 这里只存放配置项，不承担采集器注册职责；默认采集器由
 * `tracelet-sdk` 内部的装配点决定。
 */
data class TraceletConfig(
    val thresholdMs: Long = 700L,
    val storagePolicy: StoragePolicy = StoragePolicy()
) {
    init {
        require(thresholdMs > 0)
    }
}
