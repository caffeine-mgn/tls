package pw.binom.tls.socket

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import kotlinx.io.Sink
import kotlinx.io.Source
import pw.binom.tls.socket.SSLSession.State.CLOSED
import pw.binom.tls.socket.SSLSession.State.ERROR
import pw.binom.tls.socket.SSLSession.State.OK

/** Synchronous variant of [tlsConnect] for non-coroutine callers (e.g. tests). */
fun tlsConnectSync(
    source: Source,
    sink: Sink,
    sslContext: SSLContext,
    serverName: String,
    port: Int = -1,
): TlsConnection = runBlocking {
    tlsConnect(source, sink, sslContext, serverName, port)
}

/** Synchronous variant of [tlsServer]. */
fun tlsServerSync(
    source: Source,
    sink: Sink,
    sslContext: SSLContext,
): TlsConnection = runBlocking {
    tlsServer(source, sink, sslContext)
}

/**
 * Performs a TLS handshake as the client, then returns a [TlsConnection].
 *
 * @param source the read side of the underlying connection (peer → us)
 * @param sink   the write side (us → peer)
 * @param sslContext the SSL context (key/trust material, protocol)
 * @param serverName SNI value sent during the handshake
 * @param port port for SNI; -1 if unknown
 */
suspend fun tlsConnect(
    source: Source,
    sink: Sink,
    sslContext: SSLContext,
    serverName: String,
    port: Int = -1,
): TlsConnection {
    val session = sslContext.clientSession(serverName, port)
    val connection = TlsConnection(session, source, sink)
    runHandshake(session, source, sink)
    return connection
}

/** Server-side counterpart of [tlsConnect]. */
suspend fun tlsServer(
    source: Source,
    sink: Sink,
    sslContext: SSLContext,
): TlsConnection {
    val session = sslContext.serverSession()
    val connection = TlsConnection(session, source, sink)
    runHandshake(session, source, sink)
    return connection
}

private suspend fun runHandshake(session: SSLSession, source: Source, sink: Sink) {
    val tmp = Buffer()
    var iterations = 0
    while (true) {
        // 1. Ship any output the engine produced
        var drained = 0
        while (true) {
            val n = session.readNet(NET_BUF, 0, NET_BUF.size)
            if (n <= 0) break
            sink.write(NET_BUF, 0, n)
            drained += n
        }
        if (drained > 0) sink.flush()
        // 2. Feed whatever the peer sent
        tmp.clear()
        val r = source.readAtMostTo(tmp, NET_BUF.size.toLong())
        if (r > 0L) {
            val read = r.toInt()
            tmp.readAtMostTo(NET_BUF, 0, read)
            session.writeNet(NET_BUF, 0, read)
        }
        // 3. Probe — does the engine consider itself done?
        val probe = session.writeApp(EMPTY, 0, 0)
        println("handshake iter=$iterations drained=$drained r=$r probe=${probe.state}")
        when (probe.state) {
            OK -> {
                while (true) {
                    val m = session.readNet(NET_BUF, 0, NET_BUF.size)
                    if (m <= 0) break
                    sink.write(NET_BUF, 0, m)
                    sink.flush()
                }
                return
            }
            CLOSED -> throw TLSHandshakeException("handshake: remote closed")
            ERROR -> throw TLSHandshakeException("handshake: engine error")
            SSLSession.State.WANT_READ, SSLSession.State.WANT_WRITE -> {
                if (r == 0L) delay(1)  // give the peer a chance to send
            }
            else -> error("Unexpected handshake state: ${probe.state}")
        }
        iterations++
        if (iterations > 100) error("handshake: bail out after ${iterations} iterations")
    }
}

private val NET_BUF = ByteArray(16 * 1024)
private val EMPTY = ByteArray(0)
