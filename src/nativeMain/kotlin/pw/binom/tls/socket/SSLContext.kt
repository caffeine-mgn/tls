@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

package pw.binom.tls.socket

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import openssl3.ERR_print_errors_fp
import openssl3.OPENSSL_init_ssl
import openssl3.SSL
import openssl3.SSL_CTX
import openssl3.SSL_CTX_free
import openssl3.SSL_CTX_new
import openssl3.SSL_new
import openssl3.SSL_FILETYPE_ASN1
import openssl3.SSL_set_accept_state
import openssl3.SSL_set_connect_state
import openssl3.TLS_client_method
import openssl3.TLS_server_method
import openssl3.pw_SSL_CTX_set_default_verify_paths
import openssl3.pw_SSL_CTX_set_verify
import openssl3.pw_SSL_CTX_use_certificate_ASN1_call
import openssl3.pw_SSL_set_tlsext_host_name
import openssl3.pw_d2i_PrivateKey_PKCS8
import openssl3.pw_stderr

actual class SSLContext internal constructor(
    private val ctx: CPointer<SSL_CTX>,
) {

    actual fun clientSession(serverName: String, port: Int): SSLSession {
        val ssl = SSL_new(ctx)!!
        if (serverName.isNotEmpty()) {
            pw_SSL_set_tlsext_host_name(ssl, serverName)
        }
        SSL_set_connect_state(ssl)
        return SSLSession(ssl)
    }

    actual fun serverSession(): SSLSession {
        val ssl = SSL_new(ctx)!!
        SSL_set_accept_state(ssl)
        return SSLSession(ssl)
    }

    actual companion object {
        actual fun client(trustAll: Boolean): SSLContext {
            OPENSSL_init_ssl(0uL, null)
            val method = TLS_client_method()!!
            val ctx = SSL_CTX_new(method) ?: error("SSL_CTX_new failed")
            if (trustAll) {
                pw_SSL_CTX_set_verify(ctx, SSL_VERIFY_NONE)
            } else {
                pw_SSL_CTX_set_verify(ctx, SSL_VERIFY_PEER)
                pw_SSL_CTX_set_default_verify_paths(ctx)
            }
            return SSLContext(ctx)
        }

        actual fun server(certChainDer: List<ByteArray>, privateKeyDer: ByteArray): SSLContext {
            require(certChainDer.isNotEmpty()) { "certChainDer must not be empty" }
            OPENSSL_init_ssl(0uL, null)
            val method = TLS_server_method()!!
            val ctx = SSL_CTX_new(method) ?: error("SSL_CTX_new failed")

            val first = certChainDer.first()
            first.usePinned { pinned ->
                if (pw_SSL_CTX_use_certificate_ASN1_call(ctx, first.size.toLong(), pinned.addressOf(0).reinterpret()) != 1) {
                    ERR_print_errors_fp(pw_stderr())
                    SSL_CTX_free(ctx)
                    error("SSL_CTX_use_certificate_ASN1 failed")
                }
            }
            if (certChainDer.size > 1) {
                val chain = certChainDer.fold(ByteArray(0)) { acc, b -> acc + b }
                chain.usePinned { pinned ->
                    if (pw_SSL_CTX_use_certificate_ASN1_call(ctx, chain.size.toLong(), pinned.addressOf(0).reinterpret()) != 1) {
                        SSL_CTX_free(ctx)
                        error("SSL_CTX_use_certificate_chain failed")
                    }
                }
            }
            privateKeyDer.usePinned { pinned ->
                if (pw_d2i_PrivateKey_PKCS8(ctx, pinned.addressOf(0).reinterpret(), privateKeyDer.size.toLong()) == null) {
                    ERR_print_errors_fp(pw_stderr())
                    SSL_CTX_free(ctx)
                    error("SSL_CTX_use_PrivateKey (PKCS#8) failed")
                }
            }
            return SSLContext(ctx)
        }
    }
}
