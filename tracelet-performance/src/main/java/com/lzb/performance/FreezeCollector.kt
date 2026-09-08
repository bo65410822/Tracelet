package com.lzb.performance

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Printer
import com.lzb.core.Collector
import com.lzb.core.TraceletContext
import com.lzb.core.TraceletEvent

/**
 * 检测主线程卡顿 性能数据收集器
 */
class FreezeCollector : Collector {

    companion object {
        const val TYPE = "freeze"
        const val START_MSG = ">>>>> Dispatching"
        const val END_MSG = "<<<<< Finished"
        const val LINE = 100
    }

    private var mListener: Collector.EventListener? = null

    private var mDurationStart = 0L

    @Volatile
    private var mDispatching = false

    @Volatile
    private var mSamples: List<String> = emptyList()
    private var mWatchdogThread: HandlerThread? = null
    private var mWatchdogHandler: Handler? = null

    @Volatile
    private var mWatchdogTask: Runnable? = null

    private val mPrinter: Printer = Printer { message ->
        val duration: Long
        when {
            message.startsWith(START_MSG) -> {
                mDurationStart = System.nanoTime()
                mDispatching = true
                mSamples = emptyList()
                scheduleSample()
            }

            message.startsWith(END_MSG) -> {
                if (mDurationStart == 0L) {
                    return@Printer
                }
                duration = (System.nanoTime() - mDurationStart) / 1_000_000L
                cancelSample()

                val thresholdMs = TraceletContext.config.thresholdMs
                if (duration >= thresholdMs) {
                    // 超过阈值，记录卡顿
                    val attributes = mapOf(
                        "durationMs" to duration.toString(),
                        "thresholdMs" to thresholdMs.toString()
                    )
                    val event = TraceletEvent(
                        type = TYPE,
                        attributes = attributes,
                        samples = mSamples
                    )
                    mListener?.onEvent(event)
                }
                mDurationStart = 0L
                mDispatching = false
                mSamples = emptyList()
            }
        }
    }

    override fun start(listener: Collector.EventListener) {
        if (mWatchdogThread != null) return
        mListener = listener
        val thread = HandlerThread("Tracelet-FreezeWatchdog")
        thread.start()
        mWatchdogThread = thread
        mWatchdogHandler = Handler(thread.looper)
        Looper.getMainLooper().setMessageLogging(mPrinter)
    }

    override fun stop() {
        if (mWatchdogThread == null) return
        cancelSample()
        mListener = null
        mDurationStart = 0L
        mDispatching = false
        mSamples = emptyList()
        Looper.getMainLooper().setMessageLogging(null)
        mWatchdogThread?.quitSafely()
        mWatchdogThread = null
        mWatchdogHandler = null
    }

    private fun scheduleSample() {
        val task = Runnable {
            if (!mDispatching || mDurationStart == 0L) return@Runnable
            mSamples = Looper.getMainLooper().thread.stackTrace
                .take(LINE)
                .map { it.toString() }
        }
        mWatchdogTask = task
        mWatchdogHandler?.postDelayed(task, TraceletContext.config.thresholdMs)
    }

    private fun cancelSample() {
        val task = mWatchdogTask ?: return
        mWatchdogHandler?.removeCallbacks(task)
        mWatchdogTask = null
    }
}
