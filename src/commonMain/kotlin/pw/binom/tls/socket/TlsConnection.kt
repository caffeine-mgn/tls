package pw.binom.tls.socket

import kotlinx.coroutines.sync.withLock
import kotlinx.io.Buffer
import kotlinx.io.Sink
import kotlinx.io.Source

class TlsConnection internal constructor(
    private val session: SSLSession,
    private val source: Source,
    private val sink: Sink,
) : AutoCloseable {

    private val mutex = kotlinx.coroutines.sync.Mutex()
    private val netBuf = ByteArray(NET_BUF_SIZE)
    private val readBuf = Buffer()
    private val closed = kotlinx.atomicfu.atomic(false)

    suspend fun read(dst: ByteArray, offset: Int = 0, length: Int = dst.size - offset): Int {
        if (length == 0) return 0
        check(!closed.value) { "TlsConnection is closed" }
        mutex.withLock {
            while (true) {
                drainToTransport()
                val status = session.readApp(dst, offset, length)
                when (status.state) {
                    SSLSession.State.OK -> return status.bytes
                    SSLSession.State.CLOSED -> return 0
                    SSLSession.State.ERROR -> throw TlsProtocolException("read error")
                    SSLSession.State.WANT_READ -> {
                        drainToTransport()
                        val n = readNet()
                        if (n == 0) {
                            val retry = session.readApp(dst, offset, length)
                            if (retry.state == SSLSession.State.CLOSED) return 0
                            throw TlsProtocolException("Unexpected EOF during read")
                        }
                    }
                    SSLSession.State.WANT_WRITE -> { /* will drain on next iter */ }
                    else -> error("Unexpected state: ${status.state}")
                }
            }
            @Suppress("UNREACHABLE_CODE")
            0
        }
    }

    suspend fun write(src: ByteArray, offset: Int = 0, length: Int = src.size - offset) {
        if (length == 0) return
        check(!closed.value) { "TlsConnection is closed" }
        mutex.withLock {
            var pos = offset
            var remaining = length
            var iter = 0
            while (remaining > 0) {
                iter++
                if (iter > 200) error("TLS write: stuck after $iter iterations")
                // Always drain first — if the engine has pending data, ship it.
                drainToTransport()
                val status = session.writeApp(src, pos, remaining)
                when (status.state) {
                    SSLSession.State.OK -> {
                        pos += status.bytes
                        remaining -= status.bytes
                    }
                    SSLSession.State.CLOSED -> throw TlsProtocolException("write on closed session")
                    SSLSession.State.ERROR -> throw TlsProtocolException("write error")
                    SSLSession.State.WANT_READ -> {
                        // Engine may have just generated data during writeApp
                        // (e.g. ClientHello on first client wrap). Drain it
                        // before blocking on the peer, otherwise we deadlock.
                        drainToTransport()
                        val n = readNet()
                        if (n == 0) throw TlsProtocolException("EOF during write")
                    }
                    SSLSession.State.WANT_WRITE -> { /* will drain on next iter */ }
                    else -> error("Unexpected state: ${status.state}")
                }
            }
            drainToTransport()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(expect = false, update = true)) return
        runCatching { session.shutdown() }
        runCatching { drainToTransportBlocking() }
        runCatching { session.close() }
        runCatching { source.close() }
        runCatching { sink.close() }
    }

    val isClosed: Boolean get() = closed.value
    val sslSession: SSLSession get() = session

    private fun readNet(): Int {
        readBuf.clear()
        val n = source.readAtMostTo(readBuf, netBuf.size.toLong())
        if (n <= 0L) return 0
        val read = n.toInt()
        readBuf.readAtMostTo(netBuf, 0, read)
        session.writeNet(netBuf, 0, read)
        return read
    }

    private suspend fun drainToTransport() {
        while (true) {
            val n = session.readNet(netBuf, 0, netBuf.size)
            if (n <= 0) return
            sink.write(netBuf, 0, n)
            sink.flush()
        }
    }

    private fun drainToTransportBlocking() {
        while (true) {
            val n = session.readNet(netBuf, 0, netBuf.size)
            if (n <= 0) return
            runCatching { sink.write(netBuf, 0, n) }
            runCatching { sink.flush() }
        }
    }

    private companion object {
        private const val NET_BUF_SIZE = 16 * 1024
    }
}

class TlsProtocolException(message: String) : RuntimeException(message)
