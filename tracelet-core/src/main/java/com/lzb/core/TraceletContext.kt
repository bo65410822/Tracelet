package com.lzb.core

object TraceletContext {

    lateinit var config: TraceletConfig

    const val EVENT_SCHEMA_VERSION = 1

    lateinit var sessionId: String

    @Volatile
    var currentPage: String = ""

    @Volatile
    var processName: String = ""

    @Volatile
    var processId: Int = 0
}