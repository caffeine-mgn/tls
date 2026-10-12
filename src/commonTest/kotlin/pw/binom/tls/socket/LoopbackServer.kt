package pw.binom.tls.socket

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.network.sockets.port
import io.ktor.utils.io.asSink
import io.ktor.utils.io.asSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.buffered

/**
 * Platform-agnostic loopback TCP pair for tests, built on ktor-network.
 * The same ktor-network actual compiles for JVM, Android, and all
 * native targets — there is no per-platform split.
 */
class LoopbackServer internal constructor(
    private val selector: SelectorManager,
    private val serverSocket: ServerSocket,
    private val scope: CoroutineScope,
) : AutoCloseable {
    val port: Int get() = serverSocket.port

    suspend fun accept(): LoopbackSocket {
        val socket: Socket = serverSocket.accept()
        return LoopbackSocket(socket)
    }

    override fun close() {
        serverSocket.close()
        selector.close()
        scope.cancel()
    }
}

class LoopbackSocket internal constructor(
    private val socket: Socket,
) : AutoCloseable {
    fun source(): Source = socket.openReadChannel().asSource().buffered()
    fun sink(): Sink = socket.openWriteChannel(autoFlush = true).asSink().buffered()
    override fun close() {
        socket.close()
    }
}

suspend fun loopbackConnect(host: String = "127.0.0.1", port: Int): LoopbackSocket {
    val selector = SelectorManager()
    val socket: Socket = aSocket(selector).tcp().connect(host, port)
    return LoopbackSocket(socket)
}

suspend fun loopbackServer(): LoopbackServer {
    val selector = SelectorManager()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val server = aSocket(selector).tcp().bind("127.0.0.1", 0)
    return LoopbackServer(selector, server, scope)
}
