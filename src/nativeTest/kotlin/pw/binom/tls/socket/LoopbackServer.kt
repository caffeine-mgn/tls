@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

package pw.binom.tls.socket

import kotlinx.cinterop.alloc
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import platform.posix.AF_INET
import platform.posix.errno
import platform.posix.strerror
import platform.posix.INADDR_LOOPBACK
import platform.posix.SOCK_STREAM
import platform.posix.accept
import platform.posix.bind
import platform.posix.close
import platform.posix.connect
import platform.posix.htonl
import platform.posix.htons
import platform.posix.listen
import platform.posix.ntohs
import platform.posix.read
import platform.posix.shutdown
import platform.posix.socket
import platform.posix.sockaddr_in
import platform.posix.socklen_tVar
import platform.posix.write

actual class LoopbackServer actual constructor() : AutoCloseable {
    private var serverFd: Int = -1
    actual val port: Int

    init {
        memScoped {
            val fd = socket(AF_INET, SOCK_STREAM, 0)
            check(fd >= 0) { "socket() failed" }
            val addr = alloc<sockaddr_in>()
            addr.sin_family = AF_INET.convert()
            // s_addr is in network byte order. 127.0.0.1 = 0x7f, 0x00, 0x00, 0x01 (big-endian).
            // We avoid relying on INADDR_LOOPBACK being in either byte order — write bytes directly.
            // s_addr is uint32_t in network byte order. 127.0.0.1 = 0x7f, 0x00, 0x00, 0x01
            // (big-endian). On a little-endian host that value lives in memory as 0x0100007fu.
            addr.sin_addr.s_addr = 0x0100007fu
            addr.sin_port = 0u
            val bindResult = bind(fd, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().convert())
            check(bindResult == 0) { "bind() failed: $bindResult errno=$errno msg=${strerror(errno)}" }
            val listenResult = listen(fd, 1)
            check(listenResult == 0) { "listen() failed: $listenResult errno=$errno msg=${strerror(errno)}" }
            val readAddr = alloc<sockaddr_in>()
            val readAddrLen = alloc<socklen_tVar>()
            readAddrLen.value = sizeOf<sockaddr_in>().convert<platform.posix.socklen_t>()
            platform.posix.getsockname(fd, readAddr.ptr.reinterpret(), readAddrLen.ptr)
            serverFd = fd
            port = ntohs(readAddr.sin_port).convert()
        }
    }

    actual fun accept(): LoopbackSocket {
            val clientFd = accept(serverFd, null, null)
        check(clientFd >= 0) { "accept() failed: clientFd=$clientFd errno=$errno" }
            return LoopbackSocket.fromFd(clientFd)
    }

    actual override fun close() {
        if (serverFd >= 0) {
            runCatching { close(serverFd) }
            serverFd = -1
        }
    }
}

actual class LoopbackSocket actual constructor() : AutoCloseable {
    private var fd: Int = -1

    internal constructor(fd: Int) : this() {
        this.fd = fd
    }

    actual fun source(): RawSource = FdSource(fd)
    actual fun sink(): RawSink = FdSink(fd)

    actual override fun close() {
        if (fd >= 0) {
            runCatching { shutdown(fd, 2) }
            runCatching { close(fd) }
            fd = -1
        }
    }

    internal companion object {
        fun fromFd(fd: Int): LoopbackSocket = LoopbackSocket(fd)
    }
}

private class FdSource(private val fd: Int) : RawSource {
    override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
        require(byteCount <= Int.MAX_VALUE) { "byteCount too large" }
        val tmp = ByteArray(byteCount.toInt())
        val n = tmp.usePinned { pinned ->
            read(fd, pinned.addressOf(0), tmp.size.convert<ULong>())
        }
        if (n <= 0) return -1L
        sink.write(tmp, 0, n.toInt())
        return n.toLong()
    }

    override fun close() {}
}

private class FdSink(private val fd: Int) : RawSink {
    override fun write(source: Buffer, byteCount: Long) {
        require(byteCount <= Int.MAX_VALUE) { "byteCount too large" }
        val arr = ByteArray(byteCount.toInt())
        val n = source.readAtMostTo(arr).toInt()
        check(n == byteCount.toInt()) { "expected $byteCount bytes, got $n" }
        var written = 0
        arr.usePinned { pinned ->
            while (written < n) {
                val w = write(fd, pinned.addressOf(written), (n - written).convert<ULong>())
                check(w > 0) { "write() failed" }
                written += w.toInt()
            }
        }
    }

    override fun flush() {}
    override fun close() {}
}

actual fun loopbackConnect(port: Int): LoopbackSocket = memScoped {
    val fd = socket(AF_INET, SOCK_STREAM, 0)
    check(fd >= 0) { "socket() failed" }
    val addr = alloc<sockaddr_in>()
    addr.sin_family = AF_INET.convert()
    addr.sin_addr.s_addr = 0x0100007fu
    addr.sin_port = htons(port.convert())
    val c = connect(fd, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().convert())
    check(c == 0) { "connect() failed: $c errno=$errno msg=${strerror(errno)}" }
    LoopbackSocket.fromFd(fd)
}
