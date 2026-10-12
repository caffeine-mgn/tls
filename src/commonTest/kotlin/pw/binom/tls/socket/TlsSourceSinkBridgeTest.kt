package pw.binom.tls.socket

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import kotlinx.io.Sink
import kotlinx.io.Source
import pw.binom.tls.testutil.TestCertificateGenerator
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * Roundtrip test for [TlsConnection.asSource] / [TlsConnection.asSink] —
 * verifies that the blocking kotlinx-io bridge (which internally calls
 * the suspending TlsConnection.read/write via runBlocking) actually
 * moves data through the TLS layer end-to-end.
 */
class TlsSourceSinkBridgeTest {

    @Test
    fun bridge_roundtrip_over_loopback() = runBlocking {
        if (!isE2ESupported()) return@runBlocking
        val cert = TestCertificateGenerator.generateSelfSigned()
        val request = "GET /ping HTTP/1.0\r\nHost: test\r\n\r\n".encodeToByteArray()
        val expectedSize = request.size

        val server = loopbackServer()
        try {
            val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val clientScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

            val serverDeferred = serverScope.async {
                val sock = server.accept()
                sock.use {
                    val source = sock.source()
                    val sink = sock.sink()
                    val ctx = SSLContext.server(listOf(cert.certDer), cert.keyDer)
                    val conn = source.tlsServer(sink, ctx)
                    val buf = ByteArray(expectedSize)
                    var total = 0
                    while (total < buf.size) {
                        val n = conn.read(buf, total, buf.size - total)
                        require(n > 0) { "server got EOF at $total" }
                        total += n
                    }
                    conn.write(buf, 0, total)
                    conn.close()
                    total
                }
            }

            val clientResult = clientScope.async {
                val sock = loopbackConnect(port = server.port)
                sock.use {
                    val source = sock.source()
                    val sink = sock.sink()
                    val ctx = SSLContext.client(trustAll = true)
                    val conn = source.tlsClient(sink, ctx, serverName = "test", port = server.port)

                    // Write through the Sink bridge.
                    val tlsSink: Sink = conn.asSink()
                    val writeBuf = Buffer().also { it.write(request) }
                    tlsSink.write(writeBuf, request.size.toLong())
                    tlsSink.flush()

                    // Read through the Source bridge.
                    val tlsSource: Source = conn.asSource()
                    val recv = ByteArray(expectedSize)
                    var pos = 0
                    while (pos < recv.size) {
                        val n = tlsSource.readAtMostTo(recv, pos, recv.size - pos)
                        require(n > 0) { "client got EOF at $pos" }
                        pos += n
                    }

                    conn.close()
                    recv
                }
            }

            val serverTotal = serverDeferred.await()
            val clientRecv = clientResult.await()
            assertEquals(expectedSize, serverTotal)
            assertContentEquals(request, clientRecv)
        } finally {
            server.close()
        }
    }
}