package com.lzb.sdk

import android.app.Application
import android.content.Context
import android.util.Log
import com.lzb.core.Collector
import com.lzb.core.EventStore
import com.lzb.core.TraceletContext
import com.lzb.core.TraceletEvent
import com.lzb.report.JsonEventStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

internal class CollectorManager(
    private val context: Context,
    private val collectors: List<Collector>
) : Collector.EventListener {

    companion object {
        private const val TAG = "CollectorManager"
        private const val EVENT_DIR = "tracelet/events"
    }

    // 数据收集器
    private var mEventStore: EventStore? = null

    private var mEventScope: CoroutineScope = CoroutineScope(SupervisorJob())

    // SDK 内部：串行
    @OptIn(ExperimentalCoroutinesApi::class)
    private val mPersistenceDispatcher = Dispatchers.IO.limitedParallelism(1)

    // 宿主监听器：独立通道
    private val mListenerDispatcher = Dispatchers.IO

    @Volatile
    private var mEventListener: TraceletEventListener? = null

    private lateinit var mApplicationContext: Context

    private var mActivityPageTracker: ActivityPageTracker? = null

    fun initialize() {
        mApplicationContext = context.applicationContext
        val eventDirectory = File(
            mApplicationContext.filesDir,
            EVENT_DIR
        )
        mEventStore = JsonEventStore(
            directory = eventDirectory,
            dispatcher = mPersistenceDispatcher,
            storagePolicy = TraceletContext.config.storagePolicy
        )
        mActivityPageTracker = (mApplicationContext as? Application)?.let { app ->
            ActivityPageTracker().also { tracker ->
                app.registerActivityLifecycleCallbacks(tracker)
            }
        }
    }

    fun setEventListener(listener: TraceletEventListener?) {
        mEventListener = listener
    }

    suspend fun readEvents(): List<TraceletEvent> =
        mEventStore?.readAll() ?: emptyList()


    suspend fun exportEventsTo(destinationDirectory: File): File =
        mEventStore?.exportEventsTo(destinationDirectory) ?: destinationDirectory


    fun clearEvents(onComplete: (Throwable?) -> Unit = {}) {
        mEventScope.launch(mPersistenceDispatcher) {
            try {
                mEventStore?.clearAll()
                onComplete(null)
            } catch (e: Throwable) {
                Log.e(TAG, "clearEvents: e:", e)
                onComplete(e)
            }
        }
    }

    /**
     * 启动采集
     */
    fun start() {
        collectors.forEach {
            it.start(this)
        }
    }

    /**
     * 停止采集
     */
    fun stop() {
        collectors.forEach {
            it.stop()
        }
    }


    fun close() {
        stop()
        mEventScope.cancel()
        mActivityPageTracker?.let { tracker ->
            (mApplicationContext as? Application)?.unregisterActivityLifecycleCallbacks(tracker)
        }
        mActivityPageTracker = null
        mEventStore = null
        mEventListener = null
    }

    override fun onEvent(event: TraceletEvent) {
        val traceletEvent = event.copy(
            threadName = event.threadName.ifBlank {
                Thread.currentThread().name
            },
            threadId = event.threadId.takeIf {
                it != 0L
            } ?: Thread.currentThread().id,
            processId = TraceletContext.processId,
            processName = TraceletContext.processName
        )
        mEventScope.launch(mListenerDispatcher) {
            try {
                mEventListener?.onEvent(traceletEvent)
            } catch (e: Exception) {
                Log.e(TAG, "onEvent: failed, e:", e)
            }
        }
        mEventScope.launch(mPersistenceDispatcher) {
            try {
                mEventStore?.write(traceletEvent)
            } catch (e: Exception) {
                Log.e(TAG, "write event failed, e:", e)
            }
        }
    }
}
