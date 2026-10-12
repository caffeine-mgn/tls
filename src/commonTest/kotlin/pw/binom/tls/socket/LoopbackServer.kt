package pw.binom.tls.socket

import kotlinx.io.RawSink
import kotlinx.io.RawSource

/**
 * Platform-agnostic loopback TCP pair for tests. Each target supplies its
 * own actual implementation (java.net.ServerSocket + Socket on JVM/Android,
 * POSIX `socket`/`bind`/`listen`/`accept`/`connect` on native).
 *
 * Use [LoopbackServer] to open a server, [LoopbackServer.accept] to receive
 * a connection, and [loopbackConnect] to dial one. Each platform implements
 * [LoopbackSocket] as a simple wrapper around its native socket primitive.
 */
expect class LoopbackServer() : AutoCloseable {
    val port: Int
    fun accept(): LoopbackSocket
    override fun close()
}

expect class LoopbackSocket() : AutoCloseable {
    fun source(): RawSource
    fun sink(): RawSink
    override fun close()
}

/** Open a fresh loopback socket connected to [port]. */
expect fun loopbackConnect(port: Int): LoopbackSocket
