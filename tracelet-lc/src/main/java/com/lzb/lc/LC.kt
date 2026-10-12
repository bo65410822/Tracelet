package com.lzb.lc

import com.lzb.lc.frame.LcFrameType
import com.lzb.lc.frame.ParsedFrame
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.random.Random

class LC(
    private val listener: LcListener,
    private val handshaker: Handshaker? = null,   // 可选：应用层握手；不传 = TCP 连上即就绪
    // 可选：注入 TLS/自定义 socket 工厂（返回已连接的 socket）；不传 = 普通 TCP。
    // 单向/双向 TLS、证书固定等全在工厂里配，LC 不感知。
    private val socketFactory: (host: String, port: Int, connectTimeoutMs: Int) -> Socket =
        { h, p, t -> Socket().apply { connect(InetSocketAddress(h, p), t) } },
) {

    /** 连接配置：必须在首次 connect() 之前设置；连接建立后更改不会生效（已被 transport 捕获）。 */
    var lcConfig: LcConfig = LcConfig()
    private val lock = Any()
    private var epoch = 0
    private var userClosed = false
    private var attempt = 0
    private var host = "";
    private var port = 0
    private var pending: ScheduledFuture<*>? = null
    private var scheduler: ScheduledThreadPoolExecutor? = null

    @Volatile
    internal var transport: TcpTransport? = null
    private val qLock = ReentrantLock()
    private val notEmpty = qLock.newCondition()      // 队列非空
    private val connected = qLock.newCondition()     // 有可发送连接
    private var connectedFlag = false
    private val deque = ArrayDeque<ByteArray>()      // 业务待发数据
    private var senderThread: Thread? = null

    @Volatile
    private var running = false

    fun connect(host: String, port: Int) = synchronized(lock) {
        this.host = host; this.port = port
        userClosed = false; attempt = 0
        if (!running) {
            running = true
            senderThread = Thread(::sendLoop, "lc-sender").apply {
                isDaemon = true;start()
            }
        }
        ensureScheduler()
        scheduleConnect(0)
    }

    private fun scheduleConnect(delayMs: Long) {
        pending?.cancel(false)
        val myEpoch = ++epoch
        pending = scheduler?.schedule({ doConnect(myEpoch) }, delayMs, TimeUnit.MILLISECONDS)
    }

    private fun doConnect(myEpoch: Int) {
        val old: TcpTransport?
        val t: TcpTransport
        val h: String
        val p: Int
        synchronized(lock) {
            if (myEpoch != epoch || userClosed) return   // 已被取代/已关
            old = transport
            h = host; p = port
            t = TcpTransport(socketFactory = socketFactory, config = lcConfig, listener = relay(myEpoch))
            transport = t
        }
        old?.close()
        t.connect(h, p)
    }

    private fun ensureScheduler() {
        if (scheduler == null)
            scheduler = ScheduledThreadPoolExecutor(1) { r ->
                Thread(r, "lc-conn").apply { isDaemon = true }
            }
    }

    private fun relay(myEpoch: Int) = object : TransportListener {
        private var handshakeDone = false

        override fun onConnected() {
            synchronized(lock) { if (myEpoch != epoch || userClosed) return; attempt = 0 }
            if (handshaker == null) {
                handshakeDone = true
                markReady(myEpoch)                       // 无握手：TCP 连上即就绪
            } else {
                handshakeDone = false                    // 等握手成功才就绪
                handshaker.start { payload -> transport?.send(LcFrameType.HANDSHAKE, payload) }
            }
        }

        override fun onFrames(frames: List<ParsedFrame>) {
            synchronized(lock) { if (myEpoch != epoch || userClosed) return }
            for (f in frames) when (f.frameType) {
                LcFrameType.HANDSHAKE ->
                    if (handshaker != null && !handshakeDone) {
                        when (handshaker.onFrame(f.payload)) {
                            HandshakeResult.CONTINUE -> {}
                            HandshakeResult.SUCCESS -> { handshakeDone = true; markReady(myEpoch) }
                            HandshakeResult.FAIL -> failHandshake(myEpoch)
                        }
                    }
                LcFrameType.DATA ->
                    if (handshakeDone) listener.onMessage(f.payload)   // 握手完成前不投递业务数据
                else -> {}
            }
        }

        override fun onDisconnected(reason: Throwable?) {
            var terminal = false
            synchronized(lock) {
                if (myEpoch != epoch || userClosed) return   // 旧连接/用户已关 → 静默，不动 connectedFlag
                attempt++
                val max = lcConfig.maxReconnectAttempts
                if (max in 0 until attempt) terminal = true  // 超过上限 → 放弃
                else scheduleConnect(backoff(attempt))
            }
            val wasReady = qLock.withLock { val w = connectedFlag; connectedFlag = false; w }   // 清就绪并取旧值
            when {
                terminal -> listener.onDisconnected(reason)       // 彻底放弃，才算真·断开
                wasReady -> listener.onConnectionLost(reason)     // 已就绪连接掉线 → 通知业务，SDK 自动重连中
                // 未就绪的重连尝试失败 → 静默重试，不打扰业务
            }
        }
    }

    // 握手成功 / 无握手 → 标记就绪：唤醒 sender、通知业务（仍是当前连接才生效）
    private fun markReady(myEpoch: Int) {
        synchronized(lock) { if (myEpoch != epoch || userClosed) return }
        qLock.withLock { connectedFlag = true; connected.signalAll() }
        listener.onConnected()
    }

    // 握手失败（如鉴权被拒）→ 停止自动重连、关连接、上报业务；需业务重新 connect() 重试
    private fun failHandshake(myEpoch: Int) {
        val old: TcpTransport?
        synchronized(lock) {
            if (myEpoch != epoch || userClosed) return
            userClosed = true
            pending?.cancel(false)
            old = transport; transport = null
            scheduler?.execute { old?.close() }
        }
        qLock.withLock { connectedFlag = false }
        listener.onDisconnected(HandshakeException("handshake rejected"))
    }

    /**
     * 发送一条业务数据帧（DATA）。返回 true 仅表示已写入发送缓冲，非业务确认；未连接返回 false。
     * 不加锁：读 @Volatile 的 transport 当前引用后交给其线程安全的 send，避免被 connect/close 阻塞。
     */
    fun send(payload: ByteArray): Boolean = qLock.withLock {
        if (deque.size >= lcConfig.sendQueueCapacity) {
            when (lcConfig.overflowPolicy) {
                OverflowPolicy.DROP_OLDEST -> deque.removeFirst()
                OverflowPolicy.DROP_NEWEST -> return@withLock false
            }
        }
        deque.addLast(payload)
        notEmpty.signal()
        true
    }

    private fun sendLoop() {
        while (running) {
            val item = qLock.withLock {
                while (running && deque.isEmpty()) notEmpty.await()
                if (!running) return
                deque.first()
            }
            val ok = qLock.withLock { connectedFlag } && transport?.send(LcFrameType.DATA, item) == true
            qLock.withLock {
                when {
                    ok -> deque.removeFirst()
                    lcConfig.sendPolicy == SendPolicy.DROP_IF_UNSENDABLE -> deque.removeFirst() // 丢这条，继续
                    lcConfig.sendPolicy == SendPolicy.BUFFER_AND_RETRY -> {
                        while (running && !connectedFlag) connected.await()
                    } // 保这条，继续
                    else -> {}
                }
            }
        }
    }


    fun disconnect() = synchronized(lock) {
        userClosed = true
        epoch++
        pending?.cancel(false)
        val old = transport; transport = null
        scheduler?.execute { old?.close() }     // 关连接也排进 scheduler → 排在 in-flight doConnect 之后，彻底无竞态
    }

    private fun backoff(n: Int): Long {
        val shift = (n - 1).coerceAtMost(30)              // 防 1L shl 溢出
        val cap = minOf(lcConfig.backoffBaseMs shl shift, lcConfig.backoffMaxMs)
        return cap / 2 + Random.nextLong(cap / 2 + 1)      // equal jitter
    }

    fun shutdown() {
        disconnect()
        synchronized(lock) {
            scheduler?.shutdown()
            scheduler = null
        }
        running = false
        qLock.withLock {
            notEmpty.signalAll()
            connected.signalAll()
        }
    }

}

/**
 * 业务回调。注意：回调可能来自 SDK 内部的连接/读/发送线程，**不保证在主线程**，
 * 且不要在回调里做耗时/阻塞操作（会拖慢收包与心跳）。
 */
interface LcListener {
    fun onConnected()                              // (重)连成功、就绪可收发（有握手则握手成功后）
    fun onMessage(payload: ByteArray)              // 只给业务数据，不给 seq/frameType/ParsedFrame
    fun onConnectionLost(reason: Throwable?) {}    // 已就绪连接掉线，SDK 正在自动重连（默认空实现，可选覆盖）
    fun onDisconnected(reason: Throwable?)         // 终态：放弃重连 / 握手失败；需业务重新 connect()
}

/** 可选的应用层握手：TCP 连上后、业务收发前执行；握手成功才算连接就绪。 */
interface Handshaker {
    /** TCP 连上后调用：用 send 发送握手帧（内容由上层/协议决定，可多次调用）。 */
    fun start(send: (payload: ByteArray) -> Unit)

    /** 握手阶段收到的帧交由上层判定：继续等 / 成功 / 失败。 */
    fun onFrame(payload: ByteArray): HandshakeResult
}

enum class HandshakeResult { CONTINUE, SUCCESS, FAIL }

class HandshakeException(message: String) : Exception(message)