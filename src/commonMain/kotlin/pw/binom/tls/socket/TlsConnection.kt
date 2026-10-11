package pw.binom.tls.socket

import kotlinx.coroutines.sync.withLock
import kotlinx.io.Buffer
import kotlinx.io.Sink
import kotlinx.io.Source

/**
 * Coroutine-based TLS connection. Wraps a raw [Source] (peer → us) and [Sink]
 * (us → peer) with an [SSLSession]; user code reads / writes plaintext only.
 *
 * Construct via [tlsConnect] / [tlsServer] (top-level) or the equivalent
 * extensions on [Source].
 *
 * Concurrency: a single [kotlinx.coroutines.sync.Mutex] serializes the
 * underlying session, so concurrent [read] and [write] from different
 * coroutines is safe but linearized. Most consumers have one coroutine per
 * direction.
 *
 * I/O caveat: kotlinx-io 0.9.x [Source] / [Sink] are buffered but their
 * `readAtMostTo` / `write` are blocking. Methods on this class are `suspend`
 * so they compose with other coroutine code, but a single call will block the
 * calling thread until the underlying socket produces data. If you need true
 * non-blocking I/O, wrap the call in `withContext(Dispatchers.IO)`.
 */
class TlsConnection internal constructor(
    private val session: SSLSession,
    private val source: Source,
    private val sink: Sink,
) : AutoCloseable {

    private val mutex = kotlinx.coroutines.sync.Mutex()
    private val netBuf = ByteArray(NET_BUF_SIZE)
    private val readBuf = Buffer()
    private val closed = kotlinx.atomicfu.atomic(false)

    /**
     * Reads up to [length] decrypted bytes into [dst] starting at [offset].
     * Returns the number of bytes read; 0 means the peer closed cleanly.
     */
    suspend fun read(dst: ByteArray, offset: Int = 0, length: Int = dst.size - offset): Int {
        if (length == 0) return 0
        check(!closed.value) { "TlsConnection is closed" }
        mutex.withLock {
            while (true) {
                val status = session.readApp(dst, offset, length)
                when (status.state) {
                    SSLSession.State.OK -> return status.bytes
                    SSLSession.State.CLOSED -> return 0
                    SSLSession.State.ERROR -> throw TlsProtocolException("read error")
                    SSLSession.State.WANT_READ -> {
                        val n = readNet()
                        if (n == 0) {
                            val retry = session.readApp(dst, offset, length)
                            if (retry.state == SSLSession.State.CLOSED) return 0
                            throw TlsProtocolException("Unexpected EOF during read")
                        }
                    }
                    SSLSession.State.WANT_WRITE -> drainToTransport()
                    else -> error("Unexpected state: ${status.state}")
                }
                @Suppress("UNREACHABLE_CODE")
                return 0
            }
            @Suppress("UNREACHABLE_CODE")
            0
        }
    }

    /**
     * Encrypts and ships [length] bytes from [src] starting at [offset]. Loops
     * until all bytes are accepted by the SSL engine.
     */
    suspend fun write(src: ByteArray, offset: Int = 0, length: Int = src.size - offset) {
        if (length == 0) return
        check(!closed.value) { "TlsConnection is closed" }
        mutex.withLock {
            var pos = offset
            var remaining = length
            while (remaining > 0) {
                val status = session.writeApp(src, pos, remaining)
                when (status.state) {
                    SSLSession.State.OK -> {
                        pos += status.bytes
                        remaining -= status.bytes
                    }
                    SSLSession.State.CLOSED -> throw TlsProtocolException("write on closed session")
                    SSLSession.State.ERROR -> throw TlsProtocolException("write error")
                    SSLSession.State.WANT_READ -> {
                        val n = readNet()
                        if (n == 0) throw TlsProtocolException("EOF during write")
                    }
                    SSLSession.State.WANT_WRITE -> drainToTransport()
                    else -> error("Unexpected state: ${status.state}")
                }
            }
        }
    }

    /**
     * Best-effort clean shutdown. Sends close_notify if possible, then closes
     * the underlying source / sink. Idempotent.
     */
    override fun close() {
        if (!closed.compareAndSet(expect = false, update = true)) return
        runCatching { session.shutdown() }
        runCatching { drainToTransportBlocking() }
        runCatching { session.close() }
        runCatching { source.close() }
        runCatching { sink.close() }
    }

    /** True after [close] has been called. */
    val isClosed: Boolean get() = closed.value

    /** Underlying session — exposed for advanced use (e.g. cipher info). */
    val sslSession: SSLSession get() = session

    /** Feed bytes from [source] into the session. Returns the number read; 0 on EOF. */
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
