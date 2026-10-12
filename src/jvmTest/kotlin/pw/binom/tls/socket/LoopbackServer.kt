package pw.binom.tls.socket

import kotlinx.io.RawSink
import kotlinx.io.RawSource
import kotlinx.io.asSink
import kotlinx.io.asSource
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

actual class LoopbackServer actual constructor() : AutoCloseable {
    private val server: ServerSocket = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
    actual val port: Int get() = server.localPort

    actual fun accept(): LoopbackSocket {
        val sock: Socket = server.accept()
        sock.soTimeout = 15_000
        return LoopbackSocket(sock)
    }

    actual override fun close() {
        runCatching { server.close() }
    }
}

actual class LoopbackSocket actual constructor() : AutoCloseable {
    private var socket: Socket? = null
    private val s: Socket get() = requireNotNull(socket) { "LoopbackSocket not initialised" }

    internal constructor(socket: Socket) : this() {
        this.socket = socket
    }

    actual fun source(): RawSource = s.getInputStream().asSource()
    actual fun sink(): RawSink = s.getOutputStream().asSink()
    actual override fun close() {
        runCatching { socket?.close() }
    }
}

actual fun loopbackConnect(port: Int): LoopbackSocket {
    val sock = Socket(InetAddress.getLoopbackAddress(), port)
    sock.soTimeout = 15_000
    return LoopbackSocket(sock)
}
