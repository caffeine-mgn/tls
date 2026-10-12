@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package pw.binom.tls.socket

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.refTo
import openssl3.BIO
import openssl3.BIO_new
import openssl3.BIO_read
import openssl3.BIO_s_mem
import openssl3.BIO_write
import openssl3.SSL
import openssl3.SSL_free
import openssl3.SSL_get_error
import openssl3.SSL_read
import openssl3.SSL_set_bio
import openssl3.SSL_shutdown
import openssl3.SSL_write
import openssl3.pw_BIO_set_close

/**
 * Lowest-level SSLSession backed by OpenSSL 3 via the cinterop bindings in
 * `src/nativeInterop/cinterop/openssl.def`. Uses two in-memory BIOs as the
 * network buffer so the user can read/write raw TLS records via the same
 * `readNet` / `writeNet` API the JVM actual uses.
 */
actual class SSLSession internal constructor(
    private val ssl: CPointer<SSL>,
) : AutoCloseable {

    actual enum class State { OK, WANT_READ, WANT_WRITE, CLOSED, ERROR }

    private val readBio: CPointer<BIO> = BIO_new(BIO_s_mem())!!
    private val writeBio: CPointer<BIO> = BIO_new(BIO_s_mem())!!

    init {
        pw_BIO_set_close(readBio, BIO_NOCLOSE)
        pw_BIO_set_close(writeBio, BIO_NOCLOSE)
        SSL_set_bio(ssl, readBio, writeBio)
    }

    actual fun readNet(dst: ByteArray, offset: Int, length: Int): Int {
        val n = BIO_read(writeBio, dst.refTo(offset), length)
        return if (n <= 0) 0 else n
    }

    actual fun writeNet(src: ByteArray, offset: Int, length: Int): Int {
        val n = BIO_write(readBio, src.refTo(offset), length)
        return if (n <= 0) 0 else n
    }

    actual fun writeApp(src: ByteArray, offset: Int, length: Int): SSLStatus {
        val n = SSL_write(ssl, src.refTo(offset), length)
        return mapStatus(n)
    }

    actual fun readApp(dst: ByteArray, offset: Int, length: Int): SSLStatus {
        val n = SSL_read(ssl, dst.refTo(offset), length)
        return mapStatus(n)
    }

    actual fun shutdown() {
        SSL_shutdown(ssl)
    }

    actual override fun close() {
        try {
            SSL_shutdown(ssl)
        } catch (_: Throwable) {
        }
        // SSL_set_bio() in init{} transferred ownership of both BIOs to the
        // SSL object, so SSL_free() frees them — freeing them again here
        // would be a double free (SIGSEGV in BIO_free).
        SSL_free(ssl)
    }

    private fun mapStatus(n: Int): SSLStatus {
        if (n > 0) return SSLStatus(State.OK, n)
        val err = SSL_get_error(ssl, n)
        return when (err) {
            SSL_ERROR_WANT_READ -> SSLStatus(State.WANT_READ, 0)
            SSL_ERROR_WANT_WRITE -> SSLStatus(State.WANT_WRITE, 0)
            SSL_ERROR_ZERO_RETURN -> SSLStatus(State.CLOSED, 0)
            else -> SSLStatus(State.ERROR, 0)
        }
    }
}

// --- Constants mirroring OpenSSL macros (cinterop doesn't expose macros). ---
internal const val SSL_ERROR_NONE = 0
internal const val SSL_ERROR_SSL = 1
internal const val SSL_ERROR_WANT_READ = 2
internal const val SSL_ERROR_WANT_WRITE = 3
internal const val SSL_ERROR_WANT_CONNECT = 4
internal const val SSL_ERROR_SYSCALL = 5
internal const val SSL_ERROR_ZERO_RETURN = 6
internal const val SSL_VERIFY_NONE = 0
internal const val SSL_VERIFY_PEER = 1
internal const val SSL_VERIFY_FAIL_IF_NO_PEER_CERT = 2
internal const val BIO_NOCLOSE = 0
internal const val SSL_FILETYPE_ASN1 = 1
internal const val SSL_FILETYPE_PEM = 2
