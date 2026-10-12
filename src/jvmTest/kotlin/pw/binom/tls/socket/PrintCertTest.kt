package pw.binom.tls.socket

import pw.binom.tls.testutil.TestCertificateGenerator
import java.io.File
import kotlin.test.Test

class PrintCertTest {
    @Test
    fun printTestCert() {
        val c = TestCertificateGenerator.generateSelfSigned("CN=test")
        File("/tmp/cert.der").writeBytes(c.certDer)
        File("/tmp/key.der").writeBytes(c.keyDer)
    }
}
