package pw.binom.tls.testutil

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.Security
import java.util.Date

actual object TestCertificateGenerator {
    init {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    actual fun generateSelfSigned(subject: String): TestCert {
        val name = X500Name(subject)
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val serial = BigInteger.valueOf(1L)
        val notBefore = Date(0L)
        val notAfter = Date(System.currentTimeMillis() + 100L * 365 * 24 * 60 * 60 * 1000) // 100 years
        val builder = JcaX509v3CertificateBuilder(
            name, serial, notBefore, notAfter, name, keyPair.public,
        )
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(false))
        builder.addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyEncipherment))
        val signer = JcaContentSignerBuilder("SHA256WithRSA").build(keyPair.private)
        val certHolder = builder.build(signer)
        val cert = JcaX509CertificateConverter().getCertificate(certHolder)
        val certDer = cert.encoded
        val keyDer = keyPair.private.encoded
        return TestCert(certDer, keyDer)
    }
}
