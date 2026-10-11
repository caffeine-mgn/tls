package pw.binom.tls.socket

import java.nio.ByteBuffer
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult

/**
 * Lowest-level SSLSession backed by `javax.net.ssl.SSLEngine`. Sync, byte-array
 * based — matches the OpenSSL BIO_read/BIO_write semantics, so the same user
 * code works on JVM and native.
 */
actual class SSLSession internal constructor(
    private val engine: SSLEngine,
) : AutoCloseable {

    actual enum class State { OK, WANT_READ, WANT_WRITE, CLOSED, ERROR }

    private val packetBufSize = engine.session.packetBufferSize.coerceAtLeast(1024)
    private val appBufSize = engine.session.applicationBufferSize.coerceAtLeast(1024)
    private val netIn = ByteBuffer.allocateDirect(packetBufSize * 2)
    private val netOut = ByteBuffer.allocateDirect(packetBufSize * 2)
    private val appIn = ByteBuffer.allocateDirect(appBufSize * 2)
    private val appOut = ByteBuffer.allocateDirect(appBufSize * 2)

    init {
        netOut.flip()
        netIn.flip()
        appIn.flip()
        appOut.flip()
    }

    actual fun readNet(dst: ByteArray, offset: Int, length: Int): Int {
        drainNetOut()
        netOut.flip()
        val available = netOut.remaining()
        if (available == 0) {
            netOut.compact()
            netOut.limit(netOut.capacity())
            return 0
        }
        val n = minOf(available, length)
        netOut.get(dst, offset, n)
        netOut.compact()
        netOut.limit(netOut.capacity())
        return n
    }

    actual fun writeNet(src: ByteArray, offset: Int, length: Int): Int {
        netIn.compact()
        netIn.limit(netIn.capacity())
        netIn.put(src, offset, length)
        netIn.flip()
        tryUnwrap()
        return length
    }

    actual fun writeApp(src: ByteArray, offset: Int, length: Int): SSLStatus {
        appOut.compact()
        appOut.limit(appOut.capacity())
        appOut.put(src, offset, length)
        appOut.flip()
        val r = engine.wrap(appOut, netOut)
        val state = mapStatus(r)
        return SSLStatus(state, r.bytesConsumed())
    }

    actual fun readApp(dst: ByteArray, offset: Int, length: Int): SSLStatus {
        if (appIn.remaining() == 0) {
            appIn.compact()
            appIn.limit(appIn.capacity())
            val r = engine.unwrap(netIn, appIn)
            appIn.flip()
            if (r.bytesProduced() == 0) {
                return SSLStatus(mapStatus(r), 0)
            }
        }
        val n = minOf(appIn.remaining(), length)
        appIn.get(dst, offset, n)
        return SSLStatus(State.OK, n)
    }

    actual fun shutdown() {
        engine.closeOutbound()
        try {
            appOut.compact(); appOut.limit(appOut.capacity())
            engine.wrap(appOut, netOut)
            appOut.flip()
        } catch (_: Throwable) {
        }
    }

    actual override fun close() {
        try { engine.closeOutbound() } catch (_: Throwable) {}
    }

    private fun drainNetOut() {
        if (engine.handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_WRAP) {
            appOut.compact(); appOut.limit(appOut.capacity())
            val r = engine.wrap(appOut, netOut)
            appOut.flip()
        }
        runDelegatedTasks()
    }

    private fun tryUnwrap() {
        runDelegatedTasks()
        if (engine.handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_UNWRAP ||
            engine.handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_TASK ||
            netIn.hasRemaining()
        ) {
            while (netIn.hasRemaining()) {
                appIn.compact(); appIn.limit(appIn.capacity())
                val r = engine.unwrap(netIn, appIn)
                appIn.flip()
                runDelegatedTasks()
                if (r.status == SSLEngineResult.Status.BUFFER_UNDERFLOW ||
                    r.status == SSLEngineResult.Status.CLOSED) break
                if (r.bytesProduced() == 0 && r.bytesConsumed() == 0) break
            }
        }
    }

    private fun runDelegatedTasks() {
        var t = engine.delegatedTask
        while (t != null) {
            t.run()
            t = engine.delegatedTask
        }
    }

    private fun mapStatus(r: SSLEngineResult): State = when (r.status) {
        SSLEngineResult.Status.OK -> when (r.handshakeStatus) {
            SSLEngineResult.HandshakeStatus.NEED_WRAP -> State.WANT_WRITE
            SSLEngineResult.HandshakeStatus.NEED_UNWRAP -> State.WANT_READ
            SSLEngineResult.HandshakeStatus.NEED_TASK -> State.WANT_READ  // re-probe after runDelegatedTasks
            SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING,
            SSLEngineResult.HandshakeStatus.FINISHED,
            -> State.OK
            else -> State.WANT_READ
        }
        SSLEngineResult.Status.BUFFER_UNDERFLOW -> State.WANT_READ
        SSLEngineResult.Status.BUFFER_OVERFLOW -> State.WANT_WRITE
        SSLEngineResult.Status.CLOSED -> State.CLOSED
    }
}
