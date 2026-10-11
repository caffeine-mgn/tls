package pw.binom.tls.socket

import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TlsConnectionKotlinxIoTest {

    @Test
    fun buffer_is_source_and_sink() {
        // Buffer is both a Source and a Sink, no adapter needed.
        val source = Buffer().also { it.write(byteArrayOf(1, 2, 3, 4, 5)) }
        val sink = Buffer()

        val got = source.readByteArray()
        assertTrue(
            got.contentEqualsCommon(byteArrayOf(1, 2, 3, 4, 5)),
            "got ${got.toList()}",
        )

        sink.write(byteArrayOf(10, 20, 30), 0, 3)
        sink.flush()
        val drained = sink.readByteArray()
        assertTrue(drained.contentEqualsCommon(byteArrayOf(10, 20, 30)), "drained ${drained.toList()}")
    }

    @Test
    fun ssl_status_toString() {
        val s = SSLStatus(SSLSession.State.OK, 42)
        assertTrue(s.toString().contains("OK"))
        assertTrue(s.toString().contains("42"))
        assertEquals(s, SSLStatus(SSLSession.State.OK, 42))
        assertEquals(s.hashCode(), SSLStatus(SSLSession.State.OK, 42).hashCode())
    }
}

private fun ByteArray.contentEqualsCommon(other: ByteArray): Boolean {
    if (size != other.size) return false
    for (i in indices) if (this[i] != other[i]) return false
    return true
}
