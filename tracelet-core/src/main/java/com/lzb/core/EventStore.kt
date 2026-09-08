package com.lzb.core

import java.io.File

interface EventStore {

    /**
     * Write a event to storage
     */
    suspend fun write(event: TraceletEvent)

    /**
     * Read all events from storage
     */
    suspend fun readAll(): List<TraceletEvent>

    /**
     * Export all events to a directory
     */
    suspend fun exportEventsTo(destinationDirectory: File): File

    /**
     * 清空本地已持久化的事件与残留临时文件。
     */
    suspend fun clearAll()
}
