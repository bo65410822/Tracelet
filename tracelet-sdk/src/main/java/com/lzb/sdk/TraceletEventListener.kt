package com.lzb.sdk

import com.lzb.core.TraceletEvent

interface TraceletEventListener {

    fun onEvent(event: TraceletEvent)
}