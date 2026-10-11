package pw.binom.tls.socket

import kotlinx.io.Sink
import kotlinx.io.Source

/**
 * Performs a TLS handshake as a client and returns a [TlsConnection] over
 * this [Source] (peer → us) and [sink] (us → peer).
 *
 * Example:
 * ```kotlin
 * val source: Source = /* read side of your socket */
 * val sink: Sink = /* write side of your socket */
 * val ctx = SSLContext.client()
 * val tls = source.tlsClient(sink, ctx, serverName = "example.com", port = 443)
 * tls.write("GET / HTTP/1.0\r\n\r\n".encodeToByteArray())
 * val n = tls.read(buf)
 * tls.close()
 * ```
 */
suspend fun Source.tlsClient(
    sink: Sink,
    sslContext: SSLContext,
    serverName: String,
    port: Int = -1,
): TlsConnection = tlsConnect(this, sink, sslContext, serverName, port)

/** Server-side counterpart of [tlsClient]. */
suspend fun Source.tlsServer(
    sink: Sink,
    sslContext: SSLContext,
): TlsConnection = tlsServer(this, sink, sslContext)

/** Convenience: build a client [SSLContext] with default trust and connect. */
suspend fun Source.tlsClient(
    sink: Sink,
    serverName: String,
    port: Int = -1,
    trustAll: Boolean = false,
): TlsConnection = tlsConnect(this, sink, SSLContext.client(trustAll), serverName, port)

/** Convenience: build a server [SSLContext] with the given cert + key and accept. */
suspend fun Source.tlsServer(
    sink: Sink,
    certChainDer: List<ByteArray>,
    privateKeyDer: ByteArray,
): TlsConnection = tlsServer(this, sink, SSLContext.server(certChainDer, privateKeyDer))
