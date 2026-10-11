@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package pw.binom.tls.socket

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.refTo
import openssl3.ERR_print_errors_fp
import openssl3.OPENSSL_init_ssl
import openssl3.SSL
import openssl3.SSL_CTX
import openssl3.SSL_CTX_free
import openssl3.SSL_CTX_new
import openssl3.SSL_new
import openssl3.SSL_set_accept_state
import openssl3.SSL_set_connect_state
import openssl3.TLS_client_method
import openssl3.TLS_server_method
import openssl3.pw_SSL_CTX_set_default_verify_paths
import openssl3.pw_SSL_CTX_set_verify
import openssl3.pw_SSL_CTX_use_PrivateKey_buffer
import openssl3.pw_SSL_CTX_use_certificate_buffer
import openssl3.pw_SSL_CTX_use_certificate_chain_buffer
import openssl3.pw_SSL_set_tlsext_host_name
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
            if (pw_SSL_CTX_use_certificate_buffer(ctx, first.refTo(0), first.size.toLong(), SSL_FILETYPE_ASN1) != 1) {
                ERR_print_errors_fp(pw_stderr())
                SSL_CTX_free(ctx)
                error("SSL_CTX_use_certificate_buffer failed")
            }
            if (certChainDer.size > 1) {
                val chain = certChainDer.fold(ByteArray(0)) { acc, b -> acc + b }
                if (pw_SSL_CTX_use_certificate_chain_buffer(ctx, chain.refTo(0), chain.size.toLong(), SSL_FILETYPE_ASN1) != 1) {
                    SSL_CTX_free(ctx)
                    error("SSL_CTX_use_certificate_chain_buffer failed")
                }
            }
            if (pw_SSL_CTX_use_PrivateKey_buffer(ctx, privateKeyDer.refTo(0), privateKeyDer.size.toLong(), SSL_FILETYPE_ASN1) != 1) {
                ERR_print_errors_fp(pw_stderr())
                SSL_CTX_free(ctx)
                error("SSL_CTX_use_PrivateKey_buffer failed")
            }
            return SSLContext(ctx)
        }
    }
}
