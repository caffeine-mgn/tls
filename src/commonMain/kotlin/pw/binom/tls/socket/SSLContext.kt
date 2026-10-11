package pw.binom.tls.socket

/**
 * Holds the configuration needed to start a TLS session. Use the companion
 * factories — `client(...)` for the client side, `server(...)` for the server
 * side.
 *
 * The same [SSLContext] can produce many sessions (one per accepted
 * connection on a server, one per outgoing request on a client).
 *
 * Key/certificate material is passed in as DER bytes (PKCS#8 private key +
 * X.509 certificate chain). The library does **not** build certificates or
 * generate keys; use [dev.whyoleg.cryptography](https://github.com/whyoleg/cryptography-kotlin)
 * or another tool for that.
 */
expect class SSLContext {

    /** Open a client session. The [serverName] is sent as SNI; [port] is for SNI too (-1 if unknown). */
    fun clientSession(serverName: String, port: Int = -1): SSLSession

    /** Open a server session. */
    fun serverSession(): SSLSession

    companion object {
        /**
         * Client-side [SSLContext]. If [trustAll] is true, peer certificates are
         * accepted without verification — **never use in production**. The default
         * uses the system trust store (JVM) or OpenSSL's default CA bundle
         * (`/etc/ssl/certs/ca-certificates.crt` etc. on Linux).
         */
        fun client(trustAll: Boolean = false): SSLContext

        /**
         * Server-side [SSLContext].
         *
         * @param certChainDer list of DER-encoded X.509 certificates, **leaf first**.
         * @param privateKeyDer DER-encoded private key (PKCS#8). Both RSA and EC are supported.
         */
        fun server(certChainDer: List<ByteArray>, privateKeyDer: ByteArray): SSLContext
    }
}
