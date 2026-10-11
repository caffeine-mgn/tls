package pw.binom.tls.socket

/**
 * Lowest-level SSL/TLS session. Sync, byte-array based — no async wrapper is
 * provided by this library on purpose. Users wire up their own async loop on
 * top of kotlinx-io or any other byte channel they prefer.
 *
 * State machine:
 * ```
 *   while (true) {
 *       val s = session.writeApp(data, 0, n)
 *       when (s.state) {
 *           OK -> break
 *           WANT_READ -> read from transport, then session.writeNet(...)
 *           WANT_WRITE -> write pending to transport, then session.readNet(...)
 *           CLOSED -> break
 *           ERROR -> throw
 *       }
 *   }
 * ```
 *
 * `readNet` returns bytes that the SSL engine wants to send to the peer (TLS
 * records). `writeNet` feeds bytes received from the peer into the SSL engine.
 * `readApp` returns decrypted application data. `writeApp` accepts plaintext
 * to encrypt.
 *
 * The API is intentionally identical to OpenSSL's BIO_read/BIO_write/SSL_read/
 * SSL_write — translating only what works on all targets.
 */
expect class SSLSession : AutoCloseable {

    enum class State {
        OK,
        WANT_READ,
        WANT_WRITE,
        CLOSED,
        ERROR,
    }

    override fun close()

    /**
     * Pulls bytes the SSL engine wants to send to the peer (TLS records).
     * Returns the number of bytes copied into [dst], or 0 if none are pending.
     */
    fun readNet(dst: ByteArray, offset: Int = 0, length: Int = dst.size - offset): Int

    /**
     * Feeds bytes received from the peer into the SSL engine. Returns the
     * number of bytes consumed (always equals [length] on success).
     */
    fun writeNet(src: ByteArray, offset: Int = 0, length: Int = src.size - offset): Int

    /**
     * Encrypts plaintext bytes. Use [SSLStatus.state] to drive the IO loop:
     * WANT_READ/WANT_WRITE tell you to drain/feed the transport before retrying.
     */
    fun writeApp(src: ByteArray, offset: Int = 0, length: Int = src.size - offset): SSLStatus

    /**
     * Decrypts bytes from the SSL engine into [dst]. Same WANT_* semantics.
     */
    fun readApp(dst: ByteArray, offset: Int = 0, length: Int = dst.size - offset): SSLStatus

    /** Initiates a clean shutdown. After this, only readNet/writeNet may still
     *  produce/consume close_notify records. */
    fun shutdown()
}

/**
 * Result of a `readApp` / `writeApp` call. The [state] tells the caller what
 * to do next; [bytes] is the number of application bytes consumed (for
 * `writeApp`) or produced (for `readApp`) on this call.
 */
class SSLStatus internal constructor(val state: SSLSession.State, val bytes: Int) {
    override fun toString(): String = "SSLStatus(state=$state, bytes=$bytes)"
    override fun equals(other: Any?): Boolean = other is SSLStatus && other.state == state && other.bytes == bytes
    override fun hashCode(): Int = 31 * state.hashCode() + bytes
}

class TLSException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
class TLSHandshakeException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
