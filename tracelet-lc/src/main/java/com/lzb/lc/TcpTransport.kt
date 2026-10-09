package com.lzb.lc

import android.util.Log
import com.lzb.lc.frame.DecodeOutcome
import com.lzb.lc.frame.FrameDecoder
import com.lzb.lc.frame.FrameEncoder
import com.lzb.lc.frame.LcFrameType
import com.lzb.lc.frame.ParsedFrame
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

internal class TcpTransport(
    private val socketFactory: () -> Socket = { Socket() },
    private val config: LcConfig,
    private val listener: TransportListener

) {

    companion object {
        const val TAG = "TcpTransport"
        const val READ_BUFFER_SIZE = 4 * 1024
    }

    private var socket: Socket? = null
    private var output: OutputStream? = null
    private var readThread: Thread? = null

    private val encoder = FrameEncoder()

    // 发送帧序号：每条连接单调递增，connect 时重置；仅在持锁的 send 内访问，无需原子类型
    private var nextSeq = 0

    // 一次性收口：onDisconnected 恰好回调一次；每次 connect 重置以支持重连复用
    private val disconnected = AtomicBoolean(false)

    @Synchronized
    fun connect(host: String, port: Int) {
        // 丢弃可能残留的上一条连接：关闭其 socket 并清字段。静默处理——这是发起新连接而非断开，
        // 故不回调 onDisconnected；也不 join（connect 持有本类锁，持锁 join 会与读线程死锁）。
        // 前提：仅在上一条连接已断开后重连；不要对仍存活的连接直接 connect（否则旧读线程的回调
        // 会与新连接共享的 disconnected 标志竞争，应先 close()）。
        try {
            socket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "connect: close prev e", e)
        }
        socket = null
        output = null
        readThread = null

        nextSeq = 0   // 新连接会话，发送序号从头开始
        disconnected.set(false)
        val s = socketFactory()
        try {
            s.tcpNoDelay = true
            s.keepAlive = true
            // 读侧空闲超时(SO_TIMEOUT)应与心跳层一起启用，否则空闲但健康的连接会被误断，暂不设置。
             s.soTimeout = config.heartbeatIntervalMs.toInt()
            val address = InetSocketAddress(host, port)
            s.connect(address, config.connectTimeoutMs.toInt())
            socket = s
            output = s.getOutputStream()
            val stream = s.getInputStream()
            listener.onConnected()
            readThread = Thread {
                readLoop(stream)
            }.also { thread -> thread.start() }
        } catch (e: Exception) {
            Log.e(TAG, "connect: e", e)
            try {
                s.close()
            } finally {
                socket = null
                output = null
                notifyDisconnected(e)
            }
        }
    }

    private fun readLoop(stream: InputStream) {
        val frameDecoder = FrameDecoder(config.maxPayloadBytes.toInt())
        val buffer = ByteArray(READ_BUFFER_SIZE)
        try {
            var lastInbound = System.nanoTime()
            while (true) {
                val readBytes = try {
                    stream.read(buffer)
                } catch (e: SocketTimeoutException) {
                    Log.e(TAG, "readLoop: socket timeout e", e)
                    if (System.nanoTime() - lastInbound > config.heartbeatTimeoutMs * 1000_000L){
                        notifyDisconnected(SocketTimeoutException("read timeout"))
                        break
                    }
                    send(LcFrameType.HEARTBEAT, byteArrayOf())
                    continue
                }
                catch (e: Exception) {
                    Log.e(TAG, "readLoop: e", e)
                    notifyDisconnected(e)   // 读异常（含 close 触发）也要收口，一次性保证不重复
                    break
                }
                if (readBytes == -1) {
                    notifyDisconnected(EOFException("peer closed connection"))   // 对端 FIN，非本地主动关
                    break
                }
                lastInbound = System.nanoTime()
                when (val decodeOutcome = frameDecoder.feed(buffer, readBytes)) {
                    is DecodeOutcome.Frames -> dispatch(decodeOutcome.frames)
                    is DecodeOutcome.Corrupt -> {
                        dispatch(decodeOutcome.frames)   // 先投递本轮已解析的好帧
                        // TCP 字节流 framing 一旦失步不可自愈，坏帧视为致命：断链交给上层重连
                        notifyDisconnected(IllegalStateException("frame corrupt, stream desynced"))
                        break
                    }
                }
            }
        } finally {
            // 读循环退出即连接终结：关闭底层流以释放 socket fd，
            // 即使上层未在 onDisconnected 里调用 close() 也不泄漏（与 close() 的重复关闭幂等）。
            // 只关自己持有的 stream，不碰共享字段，避免与 connect/send/close 产生竞态。
            try {
                stream.close()
            } catch (e: Exception) {
                Log.e(TAG, "readLoop: close stream e", e)
            }
        }
    }

    private fun dispatch(frames: List<ParsedFrame>) {
        val business = ArrayList<ParsedFrame>()
        for (f in frames) when (f.frameType) {
            LcFrameType.HEARTBEAT     -> send(LcFrameType.HEARTBEAT_ACK, byteArrayOf())   // 回 ACK，不投递
            LcFrameType.HEARTBEAT_ACK -> { /* 仅存活信号，lastInbound 已更新，丢弃 */ }
            else          -> business.add(f)
        }
        deliver(business)
    }

    private fun deliver(frames: List<ParsedFrame>) {
        if (frames.isEmpty()) return
        try {
            listener.onFrames(frames)
        } catch (t: Throwable) {
            Log.e(TAG, "onFrames threw", t)   // 业务回调异常隔离，不当作传输错误
        }
    }

    /**
     * 发送一帧。seq 分配、编码、写入全部在锁内完成，保证「seq 顺序 == 上线顺序」。
     * 返回 true 仅表示已写入 socket 发送缓冲，并非业务级确认（可靠投递属后续 ACK 层）。
     * 未连接时返回 false，不抛异常。
     */
    @Synchronized
    fun send(type: LcFrameType, payload: ByteArray): Boolean {
        val out = output ?: return false
        val frame = encoder.encode(type, nextSeq++, payload)
        return try {
            out.write(frame)
            out.flush()
            true
        } catch (e: Exception) {
            Log.e(TAG, "send: e", e)
            notifyDisconnected(e)
            false
        }
    }

    fun close() {
        val t: Thread?
        synchronized(this) {
            notifyDisconnected(null)   // 先收口，保证本地主动关上报 null（读线程后续异常将被忽略）
            try {
                socket?.close()
            } catch (e: Exception) {
                Log.e(TAG, "close: e", e)
            } finally {
                t = readThread
                socket = null
                output = null
                readThread = null
            }
        }
        // join 必须在释放锁之后：读线程回调可能回调进 send()（持同一把锁），持锁 join 会死锁
        t?.interrupt()
        if (t != null && t != Thread.currentThread()) t.join(1000)
        if (t?.isAlive == true) {
            Log.w(TAG, "close: readThread is still alive")
        }
    }

    private fun notifyDisconnected(reason: Throwable?) {
        if (disconnected.compareAndSet(false, true)) {
            try {
                listener.onDisconnected(reason)
            } catch (e: Exception) {
                Log.e(TAG, "notifyDisconnected: e", e)
            }
        }
    }
}

internal interface TransportListener {
    fun onConnected()
    fun onFrames(frames: List<ParsedFrame>)
    fun onDisconnected(reason: Throwable?)   // 断开/错误/坏帧
}
