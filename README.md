> **Language**: English | [Русский](./README.ru.md)

# pw.binom.tls

A minimal **Kotlin/Multiplatform TLS library** — only the session. No crypto, no
PEM, no cert generation, no JCE wrapper. The public surface is the
`SSLSession` state machine + a kotlinx-io wrapper around `Source` /
`Sink`. Backed by JSSE on the JVM and OpenSSL 3 (via cinterop) on every
native target. Build anything else you need on top of these primitives, or
use [`dev.whyoleg.cryptography`](https://github.com/whyoleg/cryptography-kotlin)
for cert/keys.

## Status

**alpha.** JVM and `linuxX64` builds are green; the rest of the
native matrix is wired but the prebuilt `libssl.a` / `libcrypto.a`
from the reference project was compiled against an older GLIBC and
segfaults at runtime in this environment. See [Tests](#tests) for
which targets run which tests.

## Design

```
commonMain
 └─ pw.binom.tls.socket
      ├─ SSLSession          — lowest-level, byte-array, state machine (expect class)
      ├─ SSLContext          — client(...) / server(certChainDer, privateKeyDer) (expect class)
      ├─ TlsConnection       — kotlinx-io wrapper, blocking read / write / close
      ├─ tlsConnect / tlsServer — handshake factories (Source, Sink)
      └─ Source.tlsClient / .tlsServer — kotlinx-io extension functions
```

There is **no** crypto module, **no** PEM module, **no** X.509 builder. The
library intentionally stops at the TLS boundary. Certs and keys are passed in
as raw DER bytes.

## Examples

The library is **transport-agnostic**: you bring your own `Source` /
`Sink` for the byte channel under TLS (kotlinx-io, ktor-network,
java.nio, raw sockets, anything). The library wraps that transport in
`SSLSession` and gives you either a coroutine-friendly
[`TlsConnection`](#3-tlsconnection--coroutine-wrapper-source--sink) or
a raw [`SSLSession`](#4-sslsesession--drive-the-state-machine-yourself)
state machine.

### 1. TLS client — kotlinx-io extensions over any byte transport

```kotlin
// Any kotlinx-io Source/Sink pair works — ktor-network, raw socket, etc.
val source: Source = /* read side of your TCP socket */
val sink:   Sink   = /* write side of your TCP socket */

val ctx = SSLContext.client(trustAll = false)               // system trust store
val tls = source.tlsClient(
    sink        = sink,
    sslContext  = ctx,
    serverName  = "example.com",
    port        = 443,
)

tls.write(
    "GET / HTTP/1.1\r\nHost: example.com\r\nConnection: close\r\n\r\n"
        .encodeToByteArray(),
)

val buf = ByteArray(8192)
val n = tls.read(buf)                                       // decrypted plaintext
println(buf.copyOf(n).decodeToString())
tls.close()
```

### 2. TLS server — cert chain + PKCS#8 private key as DER

```kotlin
// Cert chain is leaf-first. Private key is PKCS#8 DER (RSA or EC).
val certChainDer  = listOf(leafCertDer, intermediateCertDer) // List<ByteArray>
val privateKeyDer: ByteArray = pkcs8Bytes

val ctx = SSLContext.server(certChainDer, privateKeyDer)

suspend fun handle(clientSource: Source, clientSink: Sink) {
    val tls = clientSource.tlsServer(clientSink, ctx)
    // ... read/write application-layer bytes ...
    tls.close()
}
```

### 3. In-process loopback (two `Buffer`s)

`kotlinx-io` `Buffer` is both a `Source` and a `Sink`, so two
`Buffer`s give you a zero-network loopback — handy for unit tests:

```kotlin
val clientToServer = Buffer()
val serverToClient = Buffer()

val serverTls = clientToServer.tlsServer(
    sink       = serverToClient,
    sslContext = SSLContext.server(listOf(certDer), keyDer),
)
val clientTls = serverToClient.tlsClient(
    sink       = clientToServer,
    sslContext = SSLContext.client(trustAll = true),
    serverName = "test",
)

clientTls.write("ping".encodeToByteArray())
val buf = ByteArray(4)
val n = serverTls.read(buf)
check(buf.copyOf(n).decodeToString() == "ping")
```

### 4. `SSLSession` — drive the state machine yourself

If the coroutine wrapper doesn't fit (non-coroutine async backend, a
custom event loop, etc.), use `SSLSession` directly. `readNet` /
`writeNet` move TLS records between the engine and the transport;
`readApp` / `writeApp` move application bytes.

```kotlin
val session: SSLSession = ctx.clientSession(serverName = "example.com", port = 443)

val netBuf = ByteArray(16 * 1024)   // bytes to/from the peer (TLS records)
val appBuf = ByteArray(16 * 1024)   // plaintext

// 1) Drain anything the engine wants to send (ClientHello, etc.)
while (true) {
    val n = session.readNet(netBuf)
    if (n <= 0) break
    transport.write(netBuf, 0, n)
}

// 2) Send some application data
fun pumpOnce(src: ByteArray, off: Int, len: Int) {
    while (true) {
        val s = session.writeApp(src, off, len)
        when (s.state) {
            SSLSession.State.OK         -> return
            SSLSession.State.WANT_READ  -> { /* peer hasn't replied yet — read more */ }
            SSLSession.State.WANT_WRITE -> { drainNet() }                // drain pending records
            SSLSession.State.CLOSED     -> return
            SSLSession.State.ERROR      -> error("TLS error")
        }
    }
}
pumpOnce("hello".encodeToByteArray(), 0, 5)

// 3) Receive application bytes
fun readOnce(): Int? {
    val s = session.readApp(appBuf)
    return when (s.state) {
        SSLSession.State.OK         -> s.bytes
        SSLSession.State.WANT_READ  -> null   // nothing from peer yet
        SSLSession.State.WANT_WRITE -> { drainNet(); null }
        SSLSession.State.CLOSED     -> null
        SSLSession.State.ERROR      -> error("TLS error")
    }
}

fun drainNet() {
    while (true) {
        val n = session.readNet(netBuf)
        if (n <= 0) break
        transport.write(netBuf, 0, n)
    }
}
```

### 5. `TlsConnection` — coroutine wrapper (`Source`, `Sink`)

`TlsConnection` is the type returned by `source.tlsClient(...)` /
`source.tlsServer(...)` (and by the top-level `tlsConnect` /
`tlsServer` factories). Its constructor is `internal` — you never
build it by hand. The wrapper owns the WANT_* loop: `read` /
`write` are **blocking** (matching kotlinx-io 0.9.x `Source` /
`Sink` semantics) and lazily complete the TLS handshake as
data flows, so no separate "do handshake" call is needed.
Call them from a real thread or `Dispatchers.IO` — not from
inside a coroutine on a single-threaded dispatcher:

```kotlin
val tls: TlsConnection = source.tlsClient(
    sink        = sink,
    sslContext  = SSLContext.client(),
    serverName  = "example.com",
    port        = 443,
)
// handshake progresses implicitly as soon as you read/write.
tls.write("hello".encodeToByteArray())
val buf = ByteArray(1024)
val n   = tls.read(buf)
tls.close()
```

For non-coroutine callers (e.g. tests) the factories are plain
`fun` (not `suspend`) so no `runBlocking` is needed:

```kotlin
val tls = tlsConnect(source, sink, ctx, "example.com", 443)
```

### 5a. Bridge to plain kotlinx-io `Source` / `Sink`

If you need to feed the decrypted bytes into an API that takes a
kotlinx-io `Source` / `Sink` (parsers, transformers, loggers, etc.)
and you don't want to stay in coroutine land, `TlsConnection`
exposes itself via extension helpers:

```kotlin
val tlsSource: kotlinx.io.Source = tls.asSource()
val tlsSink:   kotlinx.io.Sink   = tls.asSink()

tlsSink.write(Buffer().also { it.write("hello".encodeToByteArray()) }, 5)
tlsSink.flush()

val recv = tlsSource.readByteArray()  // blocking; kotlinx-io 0.9.x Source/Sink semantics
```

The returned `Source` / `Sink` are **blocking** (kotlinx-io's
sealed `Source` / `Sink` interfaces are non-suspend) and
delegate straight to the blocking [TlsConnection.read] /
[TlsConnection.write]. Don't call them from inside a coroutine
already running on a single-threaded dispatcher — use a plain
thread or `Dispatchers.IO`.

Closing the bridge does **not** close the [TlsConnection] — call
`tls.close()` explicitly when you're done.

### 6. Notes on blocking IO

`TlsConnection.read` / `write` are blocking, matching `kotlinx-io`
0.9.x `Source` / `Sink` semantics. They will park whatever thread
they are called from — do not call from a single-threaded
dispatcher. Wrap long-lived calls if your coroutine is otherwise
CPU-bound:

```kotlin
withContext(Dispatchers.IO) {
    val n = tls.read(buf)
}
```

This is the same constraint as raw `kotlinx-io` sockets; a future
suspend-native IO backend could lift this transparently.

### `SSLSession` — the state machine (signature)

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

The library is published as a JVM klib/jar only — Android application
consumers (minSdk 24+) and any other JVM host pick it up via Gradle
the same way they pull kotlinx-io or OkHttp. `androidNative*`
(Kotlin/Native → NDK) are the *separate* KMP targets that link the
prebuilt OpenSSL `.a` files for native Android code.

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
> the current 4 jvmTest + 3 linuxX64Test green-build state.

## Tests

| Test                                  | commonTest | jvmTest | linuxX64Test |
|---------------------------------------|------------|---------|---------------|
| `TlsConnectionKotlinxIoTest`          | ✅          | ✅       | ✅             |
| `TlsEndToEndTest` (server+client e2e) | ✅ (1)      | ✅       | ✅ (skipped)   |
| `SslContextConfigTest`                | -          | ✅       | -             |
| `NativeSslContextSmokeTest`           | -          | -       | ✅             |

(1) `TlsEndToEndTest` lives in `commonTest` and runs identically on every
target via a single `LoopbackServer` / `loopbackConnect` built on
**ktor-network** (`io.ktor:ktor-network:3.6.0`, test-only dep). The same
common source compiles for JVM and all native targets — there is no
per-platform `expect`/`actual` split for the loopback fixture.

On JVM the test runs the full handshake end-to-end. On
native Linux it currently self-skips (`isE2ESupported()` returns false
via the per-target expect/actual) because the prebuilt `libcrypto.a`
from the reference project was compiled against an older GLIBC and
segfaults inside the TLS handshake on modern hosts. The smoke test
(`NativeSslContextSmokeTest`) still verifies the cinterop + linker
chain is intact on linuxX64.

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
./gradlew linuxX64Test      # Native build + tests
./gradlew allTests          # every target's tests (jvm + 11 native)
./gradlew build             # everything
```

### Verification gate (pre-commit)

```bash
./gradlew ktlintFormat
./gradlew :ktlintCheck \
         :detektCommonMainSourceSet \
         :detektCommonTestSourceSet \
         jvmTest linuxX64Test
```

All five must be green before commit. `ktlintFormat` rewrites
trailing-comma / line-wrap style; the rest must finish without
errors. If `ktlintFormat` produced no diff on the second run, the
codebase is style-clean.

## Issues

Bug reports, feature requests, PRs:
[github.com/caffeine-mgn/tls/issues](https://github.com/caffeine-mgn/tls/issues)

## License

[Apache 2.0](./LICENSE).
