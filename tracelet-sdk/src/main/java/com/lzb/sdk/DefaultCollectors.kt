package com.lzb.sdk

import com.lzb.core.Collector
import com.lzb.performance.FreezeCollector

/**
 * SDK 默认采集器装配点。
 *
 * 新增默认采集器时集中在此处声明；宿主无需感知具体实现。
 * 后续若引入注解/KSP 自动注册，也只需替换这里的装配逻辑。
 */
internal object DefaultCollectors {

    fun create(): List<Collector> = listOf(
        FreezeCollector()
    )
}
