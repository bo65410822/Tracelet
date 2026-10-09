package com.lzb.lc

import com.lzb.lc.frame.LcFrameType
import com.lzb.lc.frame.ParsedFrame

class LC(private val listener: LcListener) {

    /** 连接配置：必须在首次 connect() 之前设置；连接建立后更改不会生效（已被 transport 捕获）。 */
    var LcConfig: LcConfig = LcConfig()

    @Volatile
    internal var transport: TcpTransport? = null

    @Synchronized
    fun connect(host: String, port: Int) {
        if (transport == null) {
            transport = TcpTransport(config = LcConfig, listener = object : TransportListener {
                override fun onConnected() {
                    listener.onConnected()
                }

                override fun onFrames(frames: List<ParsedFrame>) {
                    for (frame in frames) {
                        if (frame.frameType == LcFrameType.DATA) {
                            listener.onMessage(frame.payload)
                        }
                    }
                }

                override fun onDisconnected(reason: Throwable?) {
                    listener.onDisconnected(reason)
                }
            })
        }
        val tcp: TcpTransport? = transport
        tcp?.connect(host, port)
    }

    /**
     * 发送一条业务数据帧（DATA）。返回 true 仅表示已写入发送缓冲，非业务确认；未连接返回 false。
     * 不加锁：读 @Volatile 的 transport 当前引用后交给其线程安全的 send，避免被 connect/close 阻塞。
     */
    fun send(payload: ByteArray): Boolean =
        transport?.send(LcFrameType.DATA, payload) ?: false


    fun disconnect() {
        val t = synchronized(this) {
            transport?.also { transport = null }
        }
        t?.close()
    }
}
interface LcListener {
    fun onConnected()
    fun onMessage(payload: ByteArray)          // 只给业务数据，不给 seq/frameType/ParsedFrame
    fun onDisconnected(reason: Throwable?)
}