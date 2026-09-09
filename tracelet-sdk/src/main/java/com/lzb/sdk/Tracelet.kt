package com.lzb.sdk

import android.content.Context
import com.lzb.core.TraceletConfig
import com.lzb.core.TraceletContext
import com.lzb.core.TraceletEvent
import java.io.File
import java.util.UUID
import android.os.Process
import android.util.Printer
import com.lzb.performance.DiagnosticType

object Tracelet {

    @Volatile
    private var isInitialized = false

    @Volatile
    private var isStarted = false

    private lateinit var mManager: CollectorManager

    /**
     * 初始化 Tracelet SDK
     */
    fun initialize(context: Context, config: TraceletConfig = TraceletConfig()) {
        if (isInitialized) return
        TraceletContext.processName = TraceletUtils.getProcessName()
        TraceletContext.processId = Process.myPid()
        TraceletContext.config = config
        TraceletContext.sessionId = UUID.randomUUID().toString()
        mManager = CollectorManager(
            context = context,
            collectors = DefaultCollectors.create()
        )
        mManager.initialize()
        isInitialized = true
    }


    fun setEventListener(listener: TraceletEventListener?) {
        checkInit()
        mManager.setEventListener(listener)
    }

    suspend fun readEvents(): List<TraceletEvent> {
        checkInit()
        return mManager.readEvents()
    }

    suspend fun exportEventsTo(destinationDirectory: File): File {
        checkInit()
        return mManager.exportEventsTo(destinationDirectory)
    }

    fun setMainLooperPrinter(printer: Printer?) {
        checkInit()
        mManager.register(DiagnosticType.FREEZE, {
            printer?.println(it)
        })
    }

    /**
     * 清空本地已持久化的事件与残留临时文件。
     *
     * 适合事件上传成功后或账号切换时由宿主主动调用。
     */
    fun clearLocalEvents(onComplete: (Throwable?) -> Unit = {}) {
        checkInit()
        mManager.clearEvents(onComplete)
    }

    /**
     * 启动采集
     */
    fun start() {
        checkInit()
        if (isStarted) return
        mManager.start()
        isStarted = true
    }

    private fun checkInit() {
        if (!isInitialized) {
            throw IllegalStateException("Tracelet SDK not initialize")
        }
    }

    /**
     * 停止采集
     */
    fun stop() {
        if (!isStarted) return
        isStarted = false
        mManager.stop()
    }

    /**
     * 关闭 Tracelet SDK，如果需要重新初始化，然后重新调用 initialize、start
     */
    fun close() {
        if (!isInitialized) return
        stop()
        mManager.close()
        isInitialized = false
    }
}
