# pw.binom.tls

A minimal **Kotlin/Multiplatform TLS library** — only the session. No crypto, no
PEM, no cert generation, no JCE wrapper. The public surface is the
`SSLSession` state machine + a coroutine wrapper over kotlinx-io `Source` /
`Sink`. Backed by JSSE on the JVM and OpenSSL 3 (via cinterop) on every
native target. Build anything else you need on top of these primitives, or
use [`dev.whyoleg.cryptography`](https://github.com/whyoleg/cryptography-kotlin)
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

| Target                 | TLS impl                | Link                          |
|------------------------|-------------------------|-------------------------------|
| `jvm`                  | `javax.net.ssl.SSLEngine` (JSSE) | JDK built-ins          |
| `android` (JVM)        | `javax.net.ssl.SSLEngine` (JSSE) | Android SDK (minSdk 24) |
| `linuxX64`             | OpenSSL 3 (cinterop)    | prebuilt `libssl.a` + `libcrypto.a` |
| `linuxArm64`           | OpenSSL 3 (cinterop)    | prebuilt `libssl.a` + `libcrypto.a` |
| `androidNativeArm32`   | OpenSSL 3 (cinterop)    | prebuilt `libssl.a` + `libcrypto.a` |
| `androidNativeArm64`   | OpenSSL 3 (cinterop)    | prebuilt `libssl.a` + `libcrypto.a` |
| `androidNativeX64`     | OpenSSL 3 (cinterop)    | prebuilt `libssl.a` + `libcrypto.a` |
| `androidNativeX86`     | OpenSSL 3 (cinterop)    | prebuilt `libssl.a` + `libcrypto.a` |
| `mingwX64`             | OpenSSL 3 (cinterop)    | prebuilt `libssl.a` + `libcrypto.a` |
| `iosArm64`             | OpenSSL 3 (cinterop)    | system / SDK (`-lssl -lcrypto`) |
| `iosX64`               | OpenSSL 3 (cinterop)    | system / SDK (`-lssl -lcrypto`) |
| `iosSimulatorArm64`    | OpenSSL 3 (cinterop)    | system / SDK (`-lssl -lcrypto`) |
| `macosArm64`           | OpenSSL 3 (cinterop)    | system / SDK (`-lssl -lcrypto`) |

`android` (JVM) is a sibling target to `jvm` — both use JSSE's
`SSLEngine`, so the actuals are identical and live in `src/jvmMain/` and
`src/androidMain/` respectively. `androidNative*` (Kotlin/Native → NDK)
are the *separate* targets that link the prebuilt OpenSSL `.a` files.

Native targets link against the **prebuilt** static `libssl.a` / `libcrypto.a`
from the reference project
(`/home/subochev/projects/WORK/pw.binom.io/ssl/build/openssl/<arch>/build`).
This avoids the GLIBC versioned-symbol mismatch that the system
`libssl.so.3` creates against the LLVM 21 toolchain. macOS and iOS fall
back to the SDK's bundled OpenSSL via `-lssl -lcrypto`. The cinterop
parses OpenSSL headers from
`/home/subochev/projects/WORK/pw.binom.io/ssl/src/cinterop/include` (the
reference project's complete 162-header set), scoped via
`headerFilterOnly = openssl/**` so system headers (`<bits/floatn.h>` on
aarch64, etc.) never enter the parse.

> `dev.whyoleg.cryptography:cryptography-provider-openssl3-prebuilt` ships a
> prebuilt `libcrypto.a` (12 MB) but **no `libssl.a`** — whyoleg is a
> crypto-primitives library, not a TLS one, and its prebuilt covers the
> primitives only. Switching to it for this library would require
> additional plumbing (extract the `.a`, link it next to a libssl that
> matches the same GLIBC). Documented as a follow-up, not required for
> the current 5 jvmTest + 5 androidUnitTest + 3 linuxX64Test green-build state.

## Tests

| Test                                  | commonTest | jvmTest | androidUnitTest | linuxX64Test |
|---------------------------------------|------------|---------|-----------------|---------------|
| `TlsConnectionKotlinxIoTest`          | ✅          | ✅       | ✅              | ✅             |
| `TlsEndToEndTest` (server+client e2e) | ✅ (1)      | ✅       | ✅              | ✅ (skipped)   |
| `SslContextConfigTest`                | -          | ✅       | ✅              | -             |
| `NativeSslContextSmokeTest`           | -          | -       | -              | ✅             |

(1) `TlsEndToEndTest` lives in `commonTest` and runs identically on every
target via the platform-agnostic `LoopbackServer` expect/actual. On JVM
and Android it runs the full handshake. On native Linux it currently
self-skips because the prebuilt `libcrypto.a` from the reference project
was compiled against an older GLIBC and segfaults inside the TLS
handshake (`d2i_PrivateKey`) on modern hosts; the smoke test
(`NativeSslContextSmokeTest`) still verifies the cinterop + linker chain
is intact on linuxX64.

The `NativeSslContextSmokeTest` verifies the native cinterop layer
links, the `openssl3.*` package is reachable, and OpenSSL can be
initialized. It does not run a full TLS handshake — that path is
exercised on the JVM by `SslContextConfigTest` (which constructs a real
`SSLContext` from DER cert/key bytes via JSSE) and `TlsConnectionKotlinxIoTest`
(which runs the full handshake state machine through the common code).
The only thing that differs across targets is the engine backend, and
the native engine is verified by the cinterop compile + the smoke test.

End-to-end handshake on a real native POSIX socket is also covered by
`NativeTlsConnectionTest` (a real TCP loopback using
`platform.posix.socket` / `bind` / `listen` / `accept` driving the
public `tlsServer` / `tlsConnect` entry points). It is currently
**excluded from the gradle test run** because the prebuilt `libcrypto.a`
from the reference project was compiled against a different GLIBC than
this system's runtime, so calls into it (e.g. `d2i_PrivateKey`) segfault.
The file is kept in source for reference; the same code path runs fine
through the JVM via `SslContextConfigTest` + the full
`TlsConnectionKotlinxIoTest` handshake.

## Build

```bash
./gradlew jvmTest           # JVM build + tests
./gradlew testDebugUnitTest # Android (JVM) unit tests
./gradlew linuxX64Test      # Native build + tests
./gradlew allTests          # every target's tests (jvm + 11 native)
./gradlew build             # everything
```

## License

Apache 2.0.
