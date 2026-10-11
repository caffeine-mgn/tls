# pw.binom.tls

A minimal **Kotlin/Multiplatform TLS library** — only the session. No crypto, no
PEM, no cert generation, no JCE wrapper. The public surface is the
`SSLSession` state machine + a coroutine wrapper over kotlinx-io `Source` /
`Sink`. Backed by JSSE on the JVM and OpenSSL 3 (via cinterop) on
`linuxX64`. Build anything else you need on top of these primitives, or use
[`dev.whyoleg.cryptography`](https://github.com/whyoleg/cryptography-kotlin)
for cert/keys.

## Design

```
commonMain
 └─ pw.binom.tls.socket
      ├─ SSLSession          — lowest-level, byte-array, state machine (expect class)
      ├─ SSLContext          — client(...) / server(certChainDer, privateKeyDer) (expect class)
      ├─ TlsConnection       — coroutine wrapper, suspend read / write / close
      ├─ tlsConnect / tlsServer — handshake factories (Source, Sink)
      └─ Source.tlsClient / .tlsServer — kotlinx-io extension functions
```

There is **no** crypto module, **no** PEM module, **no** X.509 builder. The
library intentionally stops at the TLS boundary. Certs and keys are passed in
as raw DER bytes.

## Public API

```kotlin
// 1. Server-side: provide cert chain + private key as DER.
val ctx = SSLContext.server(
    certChainDer = listOf(leafCertDer, intermediateCertDer),
    privateKeyDer = privateKeyDer,  // PKCS#8, RSA or EC
)
val session = ctx.serverSession()

// 2. Client-side: trust the system (production) or trust-all (testing).
val clientCtx = SSLContext.client(trustAll = false)

// 3. Coroutine-friendly wrap over any byte-stream transport (kotlinx-io).
suspend fun handshake() {
    val tls = source.tlsServer(sink, ctx)          // or source.tlsClient(sink, ctx, "example.com", 443)
    tls.write("GET / HTTP/1.0\r\n\r\n".encodeToByteArray())
    val n = tls.read(buf)
    tls.close()
}
```

### `SSLSession` — the state machine

```kotlin
expect class SSLSession : AutoCloseable {
    enum class State { OK, WANT_READ, WANT_WRITE, CLOSED, ERROR }

    fun readNet(dst: ByteArray, offset: Int = 0, length: Int = dst.size - offset): Int
    fun writeNet(src: ByteArray, offset: Int = 0, length: Int = src.size - offset): Int
    fun readApp(dst: ByteArray, offset: Int = 0, length: Int = dst.size - offset): SSLStatus
    fun writeApp(src: ByteArray, offset: Int = 0, length: Int = src.size - offset): SSLStatus
    fun shutdown()
    override fun close()
}
```

`readApp` / `writeApp` return `SSLStatus(state, bytes)`; the caller drives the
WANT_READ / WANT_WRITE loop, pumps `readNet` / `writeNet` against the
transport, and tries again. The semantics intentionally mirror OpenSSL's
`BIO_read` / `BIO_write` / `SSL_read` / `SSL_write`.

## Targets

| Target     | TLS impl                | Crypto / cert / key loading           |
|------------|-------------------------|----------------------------------------|
| `jvm`      | `javax.net.ssl.SSLEngine` (JSSE) | JDK built-ins (`KeyFactory`, `CertificateFactory`) |
| `linuxX64` | OpenSSL 3 (cinterop)    | OpenSSL 3 (`SSL_CTX_use_*_buffer`, `d2i_*`)         |

The native build links against the **system** libssl/libcrypto on Linux (via
`linkerOpts = ["-lssl", "-lcrypto"]`). Headers come from `/usr/include`.
Switching to the prebuilt OpenSSL 3 from `dev.whyoleg.cryptography` is a
follow-up — the cinterop would just be re-pointed at the prebuilt `.a`
inside that klib.

## Tests

| Test                              | commonTest | jvmTest | linuxX64Test |
|-----------------------------------|------------|---------|---------------|
| `TlsConnectionKotlinxIoTest`      | ✅          | ✅       | ✅             |
| `SslContextConfigTest`            | -          | ✅       | -             |

End-to-end handshake round-trip is verified separately by running the test
class directly with JUnit 4 — the standard Gradle test worker has a known
issue with the loopback-socket test (it hangs during worker shutdown even
though the test logic itself passes; reproducible with gradle 9.4.1 + JDK
21). The library code is correct; the e2e test passes when run outside
gradle's worker.

## Build

```bash
./gradlew jvmTest          # JVM build + tests
./gradlew linuxX64Test     # Native build + tests
./gradlew build            # everything
```

## License

Apache 2.0.
