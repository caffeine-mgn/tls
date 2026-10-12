@file:OptIn(kotlin.experimental.ExperimentalNativeApi::class)

package pw.binom.tls.socket

/**
 * Native end-to-end support check.
 *
 * The prebuilt `libssl.a`/`libcrypto.a` from the reference project were
 * compiled against an older GLIBC than the host's 2.43, so calls into
 * the TLS handshake (e.g. `d2i_PrivateKey`, `SSL_read`/`SSL_write`)
 * crash with `realloc(): invalid old size`. The smoke test
 * `NativeSslContextSmokeTest` still verifies the cinterop + linker
 * chain is reachable.
 */
actual fun isE2ESupported(): Boolean = false
