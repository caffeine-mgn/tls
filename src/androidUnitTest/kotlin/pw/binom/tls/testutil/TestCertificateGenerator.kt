package pw.binom.tls.testutil

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Security
import java.security.cert.X509Certificate
import java.util.Date

data class TestCert(val certDer: ByteArray, val keyDer: ByteArray)

object TestCertificateGenerator {

    init {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    fun generateSelfSigned(
        subject: String = "CN=test",
        notBeforeMillis: Long = System.currentTimeMillis() - 60_000L,
        notAfterMillis: Long = System.currentTimeMillis() + 365L * 24 * 3600 * 1000,
        keySizeBits: Int = 2048,
    ): TestCert {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(keySizeBits)
        val keyPair: KeyPair = kpg.generateKeyPair()

        val serial = BigInteger.valueOf(System.nanoTime())
        val issuer = X500Name(subject)
        val now = Date()
        val notBefore = Date(notBeforeMillis)
        val notAfter = Date(notAfterMillis)

        val builder: X509v3CertificateBuilder = JcaX509v3CertificateBuilder(
            issuer,
            serial,
            notBefore,
            notAfter,
            issuer,
            keyPair.public,
        )
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(false))
        builder.addExtension(
            Extension.keyUsage,
            true,
            KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyEncipherment),
        )

        val signer = JcaContentSignerBuilder("SHA256WithRSA")
            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
            .build(keyPair.private)
        val holder = builder.build(signer)
        val cert: X509Certificate = JcaX509CertificateConverter()
            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
            .getCertificate(holder)

        return TestCert(
            certDer = cert.encoded,
            keyDer = keyPair.private.encoded, // PKCS#8
        )
    }
}
