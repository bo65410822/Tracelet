package com.lzb.core

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Tracelet Event
 */
@Serializable
data class TraceletEvent(
    val schemaVersion: Int = TraceletContext.EVENT_SCHEMA_VERSION,
    val id: String = UUID.randomUUID().toString(),
    val type: String,
    val processName: String = "",
    val processId: Int = 0,
    val threadName: String = "",
    val threadId: Long = 0,
    val timestampMs: Long = System.currentTimeMillis(),
    val sessionId: String = TraceletContext.sessionId,
    val page: String = TraceletContext.currentPage,
    val attributes: Map<String, String> = emptyMap(),
    val samples: List<String> = emptyList()
)