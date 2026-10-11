package pw.binom.tls.socket

import pw.binom.tls.testutil.TestCert
import pw.binom.tls.testutil.TestCertificateGenerator
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SslContextConfigTest {

    @Test
    fun client_context_can_be_created() {
        val ctx = SSLContext.client(trustAll = true)
        assertNotNull(ctx)
        val session = ctx.clientSession("test", 443)
        assertNotNull(session)
        session.close()
    }

    @Test
    fun server_context_can_be_created() {
        val testCert: TestCert = TestCertificateGenerator.generateSelfSigned()
        val ctx = SSLContext.server(listOf(testCert.certDer), testCert.keyDer)
        assertNotNull(ctx)
        val session = ctx.serverSession()
        assertNotNull(session)
        session.close()
        assertTrue(testCert.certDer.isNotEmpty())
        assertTrue(testCert.keyDer.isNotEmpty())
    }
}
