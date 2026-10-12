package pw.binom.tls.socket

import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.buffered

private const val BRIDGE_BUF_SIZE = 16 * 1024

/**
 * Returns a [kotlinx.io.Source] view of this [TlsConnection] — useful for
 * piping the decrypted application bytes into APIs that take a kotlinx-io
 * `Source` (e.g. parsers, transformers, consumers that don't know about
 * the coroutine wrapper).
 *
 * Reads are blocking and delegate straight to [TlsConnection.read].
 * Call from a regular thread or `Dispatchers.IO` — not from inside a
 * coroutine already running on a single-threaded dispatcher.
 *
 * The returned `Source` does **not** own the [TlsConnection] — closing
 * it does not close the underlying TLS session. Call
 * [TlsConnection.close] explicitly when you're done with TLS.
 */
fun TlsConnection.asSource(): Source = object : RawSource {
    private val scratch = ByteArray(BRIDGE_BUF_SIZE)
    override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
        require(byteCount >= 0) { "byteCount: $byteCount" }
        if (byteCount == 0L) return 0L
        val want = byteCount.coerceAtMost(scratch.size.toLong()).toInt()
        val n = this@asSource.read(scratch, 0, want)
        if (n <= 0) return -1L
        sink.write(scratch, 0, n)
        return n.toLong()
    }
    override fun close() { /* do not close the underlying TlsConnection */ }
}.buffered()

/**
 * Counterpart of [asSource] for writes. Returns a [kotlinx.io.Sink] that
 * forwards `write` calls straight to [TlsConnection.write]. Same
 * blocking-IO caveat as [asSource] applies.
 *
 * TLS has no notion of "flush" (each write already ships a TLS record),
 * so [kotlinx.io.Sink.flush] is a no-op. The returned `Sink` does not
 * own the [TlsConnection] — close it explicitly when done.
 */
fun TlsConnection.asSink(): Sink = object : RawSink {
    private val scratch = ByteArray(BRIDGE_BUF_SIZE)
    override fun write(source: Buffer, byteCount: Long) {
        require(byteCount >= 0) { "byteCount: $byteCount" }
        var remaining = byteCount.coerceAtMost(Long.MAX_VALUE)
        while (remaining > 0) {
            val want = remaining.coerceAtMost(scratch.size.toLong()).toInt()
            val got = source.readAtMostTo(scratch, 0, want)
            if (got <= 0) error("Buffer exhausted: wanted $want, got $got")
            this@asSink.write(scratch, 0, got)
            remaining -= got
        }
    }
    override fun flush() { /* TLS records are emitted per writeApp */ }
    override fun close() { /* do not close the underlying TlsConnection */ }
}.buffered()
