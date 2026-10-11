package pw.binom.tls.socket

import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import pw.binom.tls.testutil.TestCertificateGenerator
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNotNull
import kotlin.test.fail

class TlsEndToEndTest {

    @Test
    fun e2e_over_real_socket() {
        val cert = TestCertificateGenerator.generateSelfSigned()
        val request = "GET /ping HTTP/1.0\r\nHost: test\r\n\r\n".encodeToByteArray()

        val server = ServerSocket(0)
        val port = server.localPort
        val pool = Executors.newSingleThreadExecutor()

        val serverResult = pool.submit<ByteArray> {
            val sock = server.accept()
            sock.use {
                runBlocking {
                    val source = sock.getInputStream().asSource().buffered()
                    val sink = sock.getOutputStream().asSink().buffered()
                    val ctx = SSLContext.server(listOf(cert.certDer), cert.keyDer)
                    val conn = source.tlsServer(sink, ctx)
                    val buf = ByteArray(4096)
                    val n = conn.read(buf)
                    require(n > 0) { "server got EOF" }
                    conn.write(buf, 0, n)
                    conn.close()
                    buf.copyOf(n)
                }
            }
        }

        val client = java.net.Socket("127.0.0.1", port)
        client.soTimeout = 10_000
        client.use {
            runBlocking {
                val source = it.getInputStream().asSource().buffered()
                val sink = it.getOutputStream().asSink().buffered()
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
            }
        }

        // Now check the server's result with a timeout
        val serverGot = try {
            serverResult.get(10, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            server.close()
            pool.shutdownNow()
            fail("server timed out: ${e.message}")
        } catch (e: Throwable) {
            server.close()
            pool.shutdownNow()
            fail("server failed: ${e.message}")
        }
        server.close()
        pool.shutdown()

        assertNotNull(serverGot)
        assertContentEquals(request, serverGot, "server got: ${serverGot.toList()}")
    }
}
