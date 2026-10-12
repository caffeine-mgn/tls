package pw.binom.tls.socket

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import pw.binom.tls.testutil.TestCert
import pw.binom.tls.testutil.TestCertificateGenerator
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.fail

class TlsEndToEndTest {

    @Test
    fun e2e_over_loopback() = runBlocking {
        if (!isE2ESupported()) return@runBlocking
        val cert = TestCertificateGenerator.generateSelfSigned()
        val request = "GET /ping HTTP/1.0\r\nHost: test\r\n\r\n".encodeToByteArray()

        val server = loopbackServer()
        try {
            val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val clientScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val serverDeferred = serverScope.async { runServer(server, cert) }
            val clientResult = clientScope.async { runClient(server.port, request) }.await()
            val serverResult = try {
                withTimeout(10_000) { serverDeferred.await() }
            } catch (e: Throwable) {
                fail("server failed: ${e.message}")
            }
            assertContentEquals(request, serverResult)
            assertContentEquals(request, clientResult)
        } finally {
            server.close()
        }
    }

    private suspend fun runServer(server: LoopbackServer, cert: TestCert): ByteArray {
        val sock = server.accept()
        sock.use {
            val source = sock.source()
            val sink = sock.sink()
            val ctx = SSLContext.server(listOf(cert.certDer), cert.keyDer)
            val conn = source.tlsServer(sink, ctx)
            val expectedSize = "GET /ping HTTP/1.0\r\nHost: test\r\n\r\n".length
            val buf = ByteArray(expectedSize)
            var total = 0
            while (total < buf.size) {
                val n = conn.read(buf, total, buf.size - total)
                require(n > 0) { "server got EOF at $total" }
                total += n
            }
            conn.write(buf, 0, total)
            conn.close()
            return buf
        }
    }

    private suspend fun runClient(port: Int, request: ByteArray): ByteArray {
        val sock = loopbackConnect(port = port)
        sock.use {
            val source = sock.source()
            val sink = sock.sink()
            val ctx = SSLContext.client(trustAll = true)
            val conn = source.tlsClient(sink, ctx, serverName = "test", port = port)
            conn.write(request)
            val resp = ByteArray(request.size)
            var pos = 0
            while (pos < resp.size) {
                val n = conn.read(resp, pos, resp.size - pos)
                require(n > 0) { "client got EOF at $pos" }
                pos += n
            }
            conn.close()
            return resp
        }
    }
}
