package pw.binom.tls.socket

import kotlinx.coroutines.runBlocking
import kotlinx.io.Sink
import kotlinx.io.Source

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
 * Starts a TLS handshake as the client and returns a [TlsConnection].
 *
 * The handshake is driven by the [TlsConnection]'s read/write calls — the
 * underlying [SSLSession] will move through the handshake states as data
 * flows in and out, and reads/writes only return plaintext once the
 * handshake is complete. The user doesn't need to call any separate
 * "do handshake" function.
 */
suspend fun tlsConnect(
    source: Source,
    sink: Sink,
    sslContext: SSLContext,
    serverName: String,
    port: Int = -1,
): TlsConnection {
    val session = sslContext.clientSession(serverName, port)
    return TlsConnection(session, source, sink)
}

/** Server-side counterpart of [tlsConnect]. */
suspend fun tlsServer(
    source: Source,
    sink: Sink,
    sslContext: SSLContext,
): TlsConnection {
    val session = sslContext.serverSession()
    return TlsConnection(session, source, sink)
}
