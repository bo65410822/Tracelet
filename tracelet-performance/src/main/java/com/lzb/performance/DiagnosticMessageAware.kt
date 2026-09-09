package com.lzb.performance


interface DiagnosticMessageAware {
    fun setDiagnosticMessage(listener: InnerMessageListener?)

    fun interface InnerMessageListener {

        fun onMessage(type: DiagnosticType, msg: String)
    }
}

enum class DiagnosticType {
    FREEZE,
    STARTUP,
    PAGE
}