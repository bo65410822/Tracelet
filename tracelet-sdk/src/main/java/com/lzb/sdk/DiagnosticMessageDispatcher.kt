package com.lzb.sdk

import com.lzb.performance.DiagnosticType

internal class DiagnosticMessageDispatcher {

    private val mListeners = mutableMapOf<DiagnosticType, ((String?) -> Unit)?>()

    fun register(type: DiagnosticType, listener: ((String?) -> Unit)?) {
        mListeners.put(type, listener)
    }

    fun unregister(type: DiagnosticType) {
        mListeners.remove(type)
    }

    fun dispatch(type: DiagnosticType, message: String) {
        mListeners[type]?.invoke(message)
    }
}