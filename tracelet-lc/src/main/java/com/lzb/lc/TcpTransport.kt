package com.lzb.lc

import android.util.Log
import com.lzb.lc.frame.DecodeOutcome
import com.lzb.lc.frame.FrameDecoder
import com.lzb.lc.frame.ParsedFrame
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
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

    // 一次性收口：onDisconnected 恰好回调一次；每次 connect 重置以支持重连复用
    private val disconnected = AtomicBoolean(false)

    @Synchronized
    fun connect(host: String, port: Int) {
        disconnected.set(false)
        val s = socketFactory()
        try {
            s.tcpNoDelay = true
            s.keepAlive = true
            // 读侧空闲超时(SO_TIMEOUT)应与心跳层一起启用，否则空闲但健康的连接会被误断，暂不设置。
//             s.soTimeout = config.heartbeatTimeoutMs.toInt()
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
            try { s.close() } finally {
                socket = null
                output = null
                notifyDisconnected(e)
            }
        }
    }

    private fun readLoop(stream: InputStream) {
        val frameDecoder = FrameDecoder(config.maxPayloadBytes)
        val buffer = ByteArray(READ_BUFFER_SIZE)
        while (true) {
            val readBytes = try {
                stream.read(buffer)
            } catch (e: Exception) {
                Log.e(TAG, "readLoop: e", e)
                notifyDisconnected(e)   // 读异常（含 close 触发）也要收口，一次性保证不重复
                break
            }
            if (readBytes == -1) {
                notifyDisconnected(EOFException("peer closed connection"))   // 对端 FIN，非本地主动关
                break
            }
            when (val decodeOutcome = frameDecoder.feed(buffer, readBytes)) {
                is DecodeOutcome.Frames -> deliver(decodeOutcome.frames)
                is DecodeOutcome.Corrupt -> {
                    deliver(decodeOutcome.frames)   // 先投递本轮已解析的好帧
                    // TCP 字节流 framing 一旦失步不可自愈，坏帧视为致命：断链交给上层重连
                    notifyDisconnected(IllegalStateException("frame corrupt, stream desynced"))
                    break
                }
            }
        }
    }

    private fun deliver(frames: List<ParsedFrame>) {
        if (frames.isEmpty()) return
        try {
            listener.onFrames(frames)
        } catch (t: Throwable) {
            Log.e(TAG, "onFrames threw", t)   // 业务回调异常隔离，不当作传输错误
        }
    }

    @Synchronized
    fun send(frame: ByteArray) {
        try {
            output?.write(frame)
            output?.flush()
        } catch (e: Exception) {
            Log.e(TAG, "send: e", e)
            notifyDisconnected(e)
        }
    }

    @Synchronized
    fun close() {
        notifyDisconnected(null)   // 先收口，保证本地主动关上报 null（读线程后续异常将被忽略）
        try {
            socket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "close: e", e)
        } finally {
            readThread?.interrupt()
            socket = null
            output = null
            readThread = null
        }
    }

    private fun notifyDisconnected(reason: Throwable?) {
        if (disconnected.compareAndSet(false, true)) {
            listener.onDisconnected(reason)
        }
    }
}

interface TransportListener {
    fun onConnected()
    fun onFrames(frames: List<ParsedFrame>)
    fun onDisconnected(reason: Throwable?)   // 断开/错误/坏帧
}
