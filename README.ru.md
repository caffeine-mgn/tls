> **Язык**: [English](./README.md) | Русский

# pw.binom.tls

Минимальная **Kotlin/Multiplatform TLS-библиотека** — только сессия.
Никаких криптопримитивов, никакого PEM, никакой генерации
сертификатов, никакой обёртки над JCE. Публичный API — это state
machine `SSLSession` + обёртка над kotlinx-io `Source` /
`Sink`. На JVM бэкенд — JSSE, на каждом нативном таргете — OpenSSL 3
(через cinterop). Всё остальное (cert/key, PEM, ASN.1, генерация
X.509) собирайте поверх этих примитивов сами или используйте
[`dev.whyoleg.cryptography`](https://github.com/whyoleg/cryptography-kotlin).

## Статус

**alpha.** JVM- и `linuxX64`-сборки зелёные; остальные нативные
таргеты подключены, но prebuilt `libssl.a` / `libcrypto.a` из
референсного проекта собран под старый GLIBC и падает в рантайме в
этой среде. Какие именно тесты на каких таргетах идут — см.
[Tests](#tests).

## Дизайн

```
commonMain
 └─ pw.binom.tls.socket
      ├─ SSLSession          — низкоуровневый, byte-array, state machine (expect class)
      ├─ SSLContext          — client(...) / server(certChainDer, privateKeyDer) (expect class)
      ├─ TlsConnection       — обёртка над kotlinx-io, блокирующие read / write / close
      ├─ tlsConnect / tlsServer — фабрики handshake-а (Source, Sink)
      └─ Source.tlsClient / .tlsServer — extension-функции kotlinx-io
```

**Нет** модуля криптографии, **нет** модуля PEM, **нет** построителя
X.509. Библиотека намеренно останавливается на границе TLS. Серты и
ключи передаются сырыми DER-байтами.

## Примеры

Библиотека **транспорт-агностична**: вы приносите свою пару
`Source` / `Sink` для байтового канала под TLS (kotlinx-io,
ktor-network, java.nio, raw-сокеты, что угодно). Библиотека
оборачивает этот транспорт в `SSLSession` и отдаёт либо
корутинно-дружественный
[`TlsConnection`](#3-tlsconnection--coroutine-wrapper-source--sink),
либо сырой
[`SSLSession`](#4-sslsesession--drive-the-state-machine-yourself)
state machine.

### 1. TLS-клиент — extension-функции kotlinx-io поверх любого байтового транспорта

```kotlin
// Подходит любая пара kotlinx-io Source/Sink — ktor-network, raw-сокет и т.д.
val source: Source = /* читающая сторона вашего TCP-сокета */
val sink:   Sink   = /* пишущая сторона вашего TCP-сокета */

val ctx = SSLContext.client(trustAll = false)               // системное хранилище доверенных сертов
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
val n = tls.read(buf)                                       // расшифрованный plaintext
println(buf.copyOf(n).decodeToString())
tls.close()
```

### 2. TLS-сервер — цепочка сертов + приватный ключ PKCS#8 как DER

```kotlin
// Цепочка сертов — leaf первым. Приватный ключ — PKCS#8 DER (RSA или EC).
val certChainDer  = listOf(leafCertDer, intermediateCertDer) // List<ByteArray>
val privateKeyDer: ByteArray = pkcs8Bytes

val ctx = SSLContext.server(certChainDer, privateKeyDer)

suspend fun handle(clientSource: Source, clientSink: Sink) {
    val tls = clientSource.tlsServer(clientSink, ctx)
    // ... чтение/запись байтов application-уровня ...
    tls.close()
}
```

### 3. In-process loopback (два `Buffer`-а)

`kotlinx-io` `Buffer` — это одновременно `Source` и `Sink`, так что
два `Buffer`-а дают loopback без сети — удобно для юнит-тестов:

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

### 4. `SSLSession` — управляйте state machine сами

Если корутинная обёртка не подходит (не-корутинный async-бэкенд,
кастомный event loop и т.д.) — используйте `SSLSession` напрямую.
`readNet` / `writeNet` двигают TLS-записи между движком и
транспортом; `readApp` / `writeApp` двигают прикладные байты.

```kotlin
val session: SSLSession = ctx.clientSession(serverName = "example.com", port = 443)

val netBuf = ByteArray(16 * 1024)   // байты к/от пира (TLS-записи)
val appBuf = ByteArray(16 * 1024)   // plaintext

// 1) Сливаем всё, что движок хочет отправить (ClientHello и т.д.)
while (true) {
    val n = session.readNet(netBuf)
    if (n <= 0) break
    transport.write(netBuf, 0, n)
}

// 2) Отправляем прикладные данные
fun pumpOnce(src: ByteArray, off: Int, len: Int) {
    while (true) {
        val s = session.writeApp(src, off, len)
        when (s.state) {
            SSLSession.State.OK         -> return
            SSLSession.State.WANT_READ  -> { /* пир ещё не ответил — читаем дальше */ }
            SSLSession.State.WANT_WRITE -> { drainNet() }                // сливаем ожидающие записи
            SSLSession.State.CLOSED     -> return
            SSLSession.State.ERROR      -> error("TLS error")
        }
    }
}
pumpOnce("hello".encodeToByteArray(), 0, 5)

// 3) Принимаем прикладные байты
fun readOnce(): Int? {
    val s = session.readApp(appBuf)
    return when (s.state) {
        SSLSession.State.OK         -> s.bytes
        SSLSession.State.WANT_READ  -> null   // от пира пока ничего нет
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

### 5. `TlsConnection` — корутинная обёртка (`Source`, `Sink`)

`TlsConnection` — это тип, возвращаемый `source.tlsClient(...)` /
`source.tlsServer(...)` (и top-level фабриками `tlsConnect` /
`tlsServer`). Конструктор — `internal`, руками его не построить.
Обёртка владеет WANT_* циклом: `read` / `write` — **блокирующие**
(по аналогии с kotlinx-io 0.9.x `Source` / `Sink` семантикой) и
лениво доводят TLS-handshake до конца по мере появления данных,
так что отдельного вызова «сделать handshake» нет.
Вызывайте их из обычного потока или `Dispatchers.IO` — не из корутины
на single-threaded диспетчере:

```kotlin
val tls: TlsConnection = source.tlsClient(
    sink        = sink,
    sslContext  = SSLContext.client(),
    serverName  = "example.com",
    port        = 443,
)
// handshake продвигается неявно, как только вы читаете/пишете.
tls.write("hello".encodeToByteArray())
val buf = ByteArray(1024)
val n   = tls.read(buf)
tls.close()
```

Для не-корутинных вызывающих (например, тестов) фабрики — обычные
`fun` (не `suspend`), так что `runBlocking` не нужен:

```kotlin
val tls = tlsConnect(source, sink, ctx, "example.com", 443)
```

### 5a. Мост к чистому kotlinx-io `Source` / `Sink`

Если вам нужно скормить расшифрованные байты в API, которое
принимает kotlinx-io `Source` / `Sink` (парсеры, трансформеры,
логгеры и т.д.), и вы не хотите оставаться в корутинном мире —
`TlsConnection` экспортирует себя через extension-хелперы:

```kotlin
val tlsSource: kotlinx.io.Source = tls.asSource()
val tlsSink:   kotlinx.io.Sink   = tls.asSink()

tlsSink.write(Buffer().also { it.write("hello".encodeToByteArray()) }, 5)
tlsSink.flush()

val recv = tlsSource.readByteArray()  // блокирующий; kotlinx-io 0.9.x Source/Sink семантика
```

Возвращённые `Source` / `Sink` — **блокирующие** (kotlinx-io-овские
sealed `Source` / `Sink` интерфейсы не-suspend) и делегируют
напрямую блокирующим [TlsConnection.read] / [TlsConnection.write].
Не вызывайте их из корутины, уже крутящейся на single-threaded
диспетчере — используйте обычный поток или `Dispatchers.IO`.

Закрытие моста **не** закрывает [TlsConnection] — вызывайте
`tls.close()` явно, когда закончили.

### 6. Заметки о блокирующем IO

`TlsConnection.read` / `write` — блокирующие, по аналогии с
`kotlinx-io` 0.9.x `Source` / `Sink` семантикой. Они заблокируют
тот поток, из которого их вызвали — не вызывайте из
single-threaded диспетчера. Оборачивайте долгие вызовы, если
ваша корутина в остальное время CPU-bound:

```kotlin
withContext(Dispatchers.IO) {
    val n = tls.read(buf)
}
```

Это то же самое ограничение, что и у сырых `kotlinx-io` сокетов;
будущий suspend-нативный IO-бэкенд снимет его прозрачно.

### `SSLSession` — state machine (сигнатура)

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

`readApp` / `writeApp` возвращают `SSLStatus(state, bytes)`;
вызывающий крутит WANT_READ / WANT_WRITE цикл, прокачивает
`readNet` / `writeNet` через транспорт и пробует снова. Семантика
намеренно повторяет OpenSSL-овские `BIO_read` / `BIO_write` /
`SSL_read` / `SSL_write`.

## Таргеты

| Таргет                | TLS-имплементация        | Линковка                              |
|-----------------------|--------------------------|---------------------------------------|
| `jvm`                 | `javax.net.ssl.SSLEngine` (JSSE) | встроенное в JDK              |
| `linuxX64`            | OpenSSL 3 (cinterop)     | prebuilt `libssl.a` + `libcrypto.a`   |
| `linuxArm64`          | OpenSSL 3 (cinterop)     | prebuilt `libssl.a` + `libcrypto.a`   |
| `androidNativeArm32`  | OpenSSL 3 (cinterop)     | prebuilt `libssl.a` + `libcrypto.a`   |
| `androidNativeArm64`  | OpenSSL 3 (cinterop)     | prebuilt `libssl.a` + `libcrypto.a`   |
| `androidNativeX64`    | OpenSSL 3 (cinterop)     | prebuilt `libssl.a` + `libcrypto.a`   |
| `androidNativeX86`    | OpenSSL 3 (cinterop)     | prebuilt `libssl.a` + `libcrypto.a`   |
| `mingwX64`            | OpenSSL 3 (cinterop)     | prebuilt `libssl.a` + `libcrypto.a`   |
| `iosArm64`            | OpenSSL 3 (cinterop)     | системное / SDK (`-lssl -lcrypto`)    |
| `iosX64`              | OpenSSL 3 (cinterop)     | системное / SDK (`-lssl -lcrypto`)    |
| `iosSimulatorArm64`   | OpenSSL 3 (cinterop)     | системное / SDK (`-lssl -lcrypto`)    |
| `macosArm64`          | OpenSSL 3 (cinterop)     | системное / SDK (`-lssl -lcrypto`)    |

Библиотека публикуется как JVM klib/jar — Android-приложения
(minSdk 24+) и любой другой JVM-хост подтягивают её через Gradle
так же, как kotlinx-io или OkHttp. `androidNative*` (Kotlin/Native →
NDK) — это **отдельные** KMP-таргеты, которые линкуют prebuilt
OpenSSL `.a`-файлы для нативного Android-кода.

Нативные таргеты линкуются с **prebuilt** статическими
`libssl.a` / `libcrypto.a` из референсного проекта
(`/home/subochev/projects/WORK/pw.binom.io/ssl/build/openssl/<arch>/build`).
Это позволяет обойти несовпадение GLIBC-версионированных символов,
которые системный `libssl.so.3` создаёт против LLVM 21 тулчейна.
macOS и iOS фолбэчат на SDK-шный OpenSSL через `-lssl -lcrypto`.
Cinterop парсит OpenSSL-овые заголовки из
`/home/subochev/projects/WORK/pw.binom.io/ssl/src/cinterop/include`
(полный набор из 162 заголовков референсного проекта), ограниченный
через `headerFilterOnly = openssl/**` — системные заголовки
(`<bits/floatn.h>` на aarch64 и т.д.) в парс не попадают.

> `dev.whyoleg.cryptography:cryptography-provider-openssl3-prebuilt`
> поставляет prebuilt `libcrypto.a` (12 МБ), но **без
> `libssl.a`** — whyoleg это библиотека криптопримитивов, а не
> TLS, и её prebuilt покрывает только примитивы. Переключение на
> неё для этой библиотеки потребовало бы дополнительной возни
> (извлечь `.a`, слинковать рядом с libssl, у которой тот же GLIBC).
> Зафиксировано как follow-up, не требуется для текущего зелёного
> состояния 4 jvmTest + 3 linuxX64Test.

## Тесты

| Тест                                  | commonTest | jvmTest | linuxX64Test |
|---------------------------------------|------------|---------|---------------|
| `TlsConnectionKotlinxIoTest`          | ✅          | ✅       | ✅             |
| `TlsEndToEndTest` (server+client e2e) | ✅ (1)      | ✅       | ✅ (skipped)   |
| `SslContextConfigTest`                | -          | ✅       | -             |
| `NativeSslContextSmokeTest`           | -          | -       | ✅             |

(1) `TlsEndToEndTest` живёт в `commonTest` и одинаково прогоняется
на каждом таргете через единый `LoopbackServer` / `loopbackConnect`,
построенный на **ktor-network** (`io.ktor:ktor-network:3.6.0`,
test-only зависимость). Один и тот же common-исходник компилируется
для JVM и всех нативных таргетов — никакого per-platform
`expect`/`actual` сплита для loopback-фикстуры нет.

На JVM тест прогоняет полный handshake end-to-end. На нативном
Linux он сейчас скипает сам себя (`isE2ESupported()` возвращает
false через per-target expect/actual), потому что prebuilt
`libcrypto.a` из референсного проекта собран под старый GLIBC и
падает внутри TLS-handshake на современных хостах. Smoke-тест
(`NativeSslContextSmokeTest`) всё же проверяет, что цепочка
cinterop + линковщика на linuxX64 целая.

`NativeSslContextSmokeTest` проверяет, что нативный cinterop-слой
линкуется, пакет `openssl3.*` достижим, и OpenSSL может быть
проинициализирован. Полный TLS-handshake он не прогоняет — этот
путь тестируется на JVM через `SslContextConfigTest` (который
собирает реальный `SSLContext` из DER-байтов серта/ключа через
JSSE) и `TlsConnectionKotlinxIoTest` (который прогоняет полный
handshake state machine через common-код). Единственное, что
отличается между таргетами — бэкенд движка, и нативный движок
проверяется cinterop-компиляцией + smoke-тестом.

End-to-end handshake на реальном нативном POSIX-сокете также
покрыт `NativeTlsConnectionTest` (реальный TCP-loopback через
`platform.posix.socket` / `bind` / `listen` / `accept`,
управляющий публичными точками входа `tlsServer` / `tlsConnect`).
Сейчас он **исключён из gradle test run**, потому что prebuilt
`libcrypto.a` из референсного проекта собран под другой GLIBC,
чем runtime этой системы, и вызовы в него (например,
`d2i_PrivateKey`) падают в segfault. Файл оставлен в исходниках
для справки; тот же путь кода нормально работает через JVM через
`SslContextConfigTest` + полный `TlsConnectionKotlinxIoTest`
handshake.

## Сборка

```bash
./gradlew jvmTest           # JVM-сборка + тесты
./gradlew linuxX64Test      # нативная сборка + тесты
./gradlew allTests          # все тесты на всех таргетах (jvm + 11 нативных)
./gradlew build             # всё
```

### Verification gate (перед коммитом)

```bash
./gradlew ktlintFormat
./gradlew :ktlintCheck \
          :detektCommonMainSourceSet \
          :detektCommonTestSourceSet \
          jvmTest linuxX64Test
```

Перед коммитом все пять должны быть зелёными. `ktlintFormat`
переписывает trailing-comma / переносы строк; остальные должны
завершиться без ошибок. Если `ktlintFormat` на втором прогоне
диффа не дал — кодстайл чистый.

## Issues

Багрепорты, фичереквесты, PR-ы:
[github.com/caffeine-mgn/tls/issues](https://github.com/caffeine-mgn/tls/issues)

## Лицензия

[Apache 2.0](./LICENSE).
