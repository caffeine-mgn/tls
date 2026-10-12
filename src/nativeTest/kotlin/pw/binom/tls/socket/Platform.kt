@file:OptIn(kotlin.experimental.ExperimentalNativeApi::class)

package pw.binom.tls.socket

/**
 * Native end-to-end support check.
 *
 * OpenSSL is now self-built per target (see `build.gradle.kts` /
 * `OpenSSLBuildTask`), so the `.a` files match the target's own libc and
 * the full handshake works — the end-to-end test is enabled on native.
 */
actual fun isE2ESupported(): Boolean = true
