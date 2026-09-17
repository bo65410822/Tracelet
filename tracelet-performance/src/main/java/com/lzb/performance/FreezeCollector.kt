package com.lzb.performance

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.util.Printer
import com.lzb.core.Collector
import com.lzb.core.TraceletContext
import com.lzb.core.TraceletEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * 检测主线程卡顿 性能数据收集器
 */
class FreezeCollector(
    private val nanoTime: () -> Long = System::nanoTime,
    private val stackSampler: () -> List<String> = {
        Looper.getMainLooper().thread.stackTrace
            .take(LINE)
            .map { it.toString() }
    }
) : Collector {

    companion object {
        private const val TAG = "FreezeCollector"
        const val TYPE = "freeze"
        const val START_MSG = ">>>>> Dispatching"
        const val END_MSG = "<<<<< Finished"
        const val LINE = 100

        var mainThreadPrinter: Printer? = null
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


    @OptIn(ExperimentalCoroutinesApi::class)
    private var mScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    private val mPrinter: Printer = Printer { message ->
        mScope.launch {
            try {
                mainThreadPrinter?.println(message)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "diagnostic callback failed", e)
            }
        }
        onLooperMessage(message)?.let { mListener?.onEvent(it) }
    }

    internal fun onLooperMessage(message: String): TraceletEvent? {
        when {
            message.startsWith(START_MSG) -> {
                mDurationStart = nanoTime()
                mDispatching = true
                mSamples = emptyList()
                scheduleSample()
            }

            message.startsWith(END_MSG) -> {
                if (mDurationStart == 0L) {
                    Log.i(TAG, "mDurationStart: $mDurationStart")
                    return null
                }
                val duration = (nanoTime() - mDurationStart) / 1_000_000L
                cancelSample()

                val thresholdMs = TraceletContext.config.thresholdMs
                val event = if (duration >= thresholdMs) {
                    TraceletEvent(
                        type = TYPE,
                        attributes = mapOf(
                            "durationMs" to duration.toString(),
                            "thresholdMs" to thresholdMs.toString()
                        ),
                        samples = mSamples
                    )
                } else {
                    null
                }
                mDurationStart = 0L
                mDispatching = false
                mSamples = emptyList()
                return event
            }
        }
        return null
    }

    internal fun sampleNow() {
        if (mDispatching && mDurationStart != 0L) {
            mSamples = stackSampler().take(LINE)
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
        Looper.getMainLooper().setMessageLogging(mainThreadPrinter)
        mWatchdogThread?.quitSafely()
        mWatchdogThread = null
        mWatchdogHandler = null
        mScope.coroutineContext.cancelChildren()
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
