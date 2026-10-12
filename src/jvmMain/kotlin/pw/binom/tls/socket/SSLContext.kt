package pw.binom.tls.socket

import java.security.KeyFactory
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate as JdkCert
import javax.net.ssl.SSLContext as JdkSSLContext
import javax.net.ssl.TrustManager as JdkTrustManager

actual class SSLContext internal constructor(
    private val jvmContext: JdkSSLContext,
) {

    actual fun clientSession(serverName: String, port: Int): SSLSession {
        val engine = if (serverName.isNotEmpty()) {
            jvmContext.createSSLEngine(serverName, if (port < 0) 0 else port)
        } else {
            jvmContext.createSSLEngine()
        }
        engine.useClientMode = true
        return SSLSession(engine)
    }

    actual fun serverSession(): SSLSession {
        val engine = jvmContext.createSSLEngine()
        engine.useClientMode = false
        return SSLSession(engine)
    }

    actual companion object {
        actual fun client(trustAll: Boolean): SSLContext {
            val ctx = JdkSSLContext.getInstance("TLS")
            val tm: Array<JdkTrustManager>? = if (trustAll) arrayOf(TrustAllManager) else null
            ctx.init(null, tm, null)
            return SSLContext(ctx)
        }

        actual fun server(certChainDer: List<ByteArray>, privateKeyDer: ByteArray): SSLContext {
            require(certChainDer.isNotEmpty()) { "certChainDer must not be empty" }
            val certFactory = CertificateFactory.getInstance("X.509")
            val certs: List<JdkCert> = certChainDer.map {
                certFactory.generateCertificate(it.inputStream()) as JdkCert
            }
            val key = decodePrivateKey(privateKeyDer)

            val ks = KeyStore.getInstance(KeyStore.getDefaultType())
            ks.load(null, null)
            ks.setKeyEntry("alias", key, null, certs.toTypedArray())

            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            kmf.init(ks, null)

            val ctx = JdkSSLContext.getInstance("TLS")
            ctx.init(kmf.keyManagers, null, null)
            return SSLContext(ctx)
        }
    }
}

private fun decodePrivateKey(der: ByteArray): PrivateKey {
    val spec = java.security.spec.PKCS8EncodedKeySpec(der)
    val algorithms = listOf("RSA", "EC", "DSA", "Ed25519", "Ed448")
    var lastError: Throwable? = null
    for (algo in algorithms) {
        try {
            return KeyFactory.getInstance(algo).generatePrivate(spec)
        } catch (t: Throwable) {
            lastError = t
        }
    }
    throw IllegalArgumentException("Cannot decode private key: tried $algorithms", lastError)
}

private object TrustAllManager : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out JdkCert>?, authType: String?) {}
    override fun checkServerTrusted(chain: Array<out JdkCert>?, authType: String?) {}
    override fun getAcceptedIssuers(): Array<JdkCert> = emptyArray()
}
