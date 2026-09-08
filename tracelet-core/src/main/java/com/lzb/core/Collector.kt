package com.lzb.core

/**
 * 性能数据收集器
 */
interface Collector {

    /**
     * 开始收集
     */
    fun start(listener: EventListener)

    /**
     * 停止收集
     */
    fun stop()

    interface EventListener {

        fun onEvent(event: TraceletEvent)
    }
}