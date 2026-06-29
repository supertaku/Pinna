package com.pinna.app.network

import java.net.Socket
import java.util.Collections

class ControlStreamHub(
    private val maxStreams: Int = 8,
) {
    private val streams = Collections.synchronizedSet(linkedSetOf<Socket>())

    val size: Int
        get() = streams.size

    fun tryAdd(socket: Socket): Boolean {
        synchronized(streams) {
            if (streams.size >= maxStreams) return false
            streams += socket
            return true
        }
    }

    fun remove(socket: Socket) {
        streams -= socket
        runCatching { socket.close() }
    }

    fun broadcastText(text: String) {
        val frame = RoomWebSocketFrameCodec.encodeText(text)
        val snapshot = synchronized(streams) { streams.toList() }
        snapshot.forEach { socket ->
            runCatching {
                synchronized(socket) {
                    socket.getOutputStream().write(frame)
                    socket.getOutputStream().flush()
                }
            }.onFailure {
                remove(socket)
            }
        }
    }

    fun closeAll() {
        val closeFrame = RoomWebSocketFrameCodec.encodeClose()
        val snapshot = synchronized(streams) { streams.toList() }
        snapshot.forEach { socket ->
            runCatching {
                synchronized(socket) {
                    socket.getOutputStream().write(closeFrame)
                    socket.getOutputStream().flush()
                }
            }
            remove(socket)
        }
    }
}
