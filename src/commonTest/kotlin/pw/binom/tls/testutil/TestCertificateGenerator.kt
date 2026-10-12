package pw.binom.tls.testutil

/**
 * Self-signed RSA-2048 test cert generator. Per-platform actual implementations
 * (BouncyCastle on JVM/Android, OpenSSL cinterop on native) produce
 * `TestCert(certDer, keyDer)` with `keyDer` in PKCS#8 form.
 */
expect object TestCertificateGenerator {
    fun generateSelfSigned(subject: String = "CN=test"): TestCert
}

data class TestCert(val certDer: ByteArray, val keyDer: ByteArray)
