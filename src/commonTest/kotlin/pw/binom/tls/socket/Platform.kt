package pw.binom.tls.socket

/**
 * Whether the current target can run a real end-to-end TLS handshake
 * (server+client+TLS protocol over a loopback socket).
 *
 * - JVM and Android: always true (JSSE runs in-process)
 * - Native Linux: false when the linked libssl/libcrypto is incompatible
 *   with the host GLIBC. The prebuilt `.a` from the reference project
 *   was built against an older GLIBC and segfaults on modern hosts, so
 *   native tests fall back to a smoke test in `NativeSslContextSmokeTest`.
 * - Other native targets: not exercised in this environment.
 */
expect fun isE2ESupported(): Boolean
