package de.pyryco.mobile.ui.conversations.thread

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * The provider-facing half of #932's send path, proven on plain JVM: the bounded read that ignores
 * whatever size the other app reported, and the URI guard that keeps our own files out of an upload.
 */
class AttachmentReaderTest {
    private fun bytes(size: Int) = ByteArray(size) { (it % 251).toByte() }

    @Test
    fun readBounded_returnsExactBytes_atZeroAtTheLimitAndAcrossBuffers() {
        for (size in listOf(0, 1, 8191, 8192, 8193, 20_000)) {
            val data = bytes(size)
            assertArrayEquals("size $size", data, readBounded(ByteArrayInputStream(data), maxBytes = 20_000))
        }
    }

    @Test
    fun readBounded_isNull_oneBytePastTheLimit() {
        assertNull(readBounded(ByteArrayInputStream(bytes(20_001)), maxBytes = 20_000))
    }

    @Test
    fun readBounded_stopsReading_soonAfterPassingTheLimit() {
        // A provider may report a small size and stream forever; the read must stop on its own count.
        var served = 0L
        val endless =
            object : InputStream() {
                override fun read(): Int {
                    served++
                    return 0
                }

                override fun read(
                    b: ByteArray,
                    off: Int,
                    len: Int,
                ): Int {
                    served += len
                    return len
                }
            }

        assertNull(readBounded(endless, maxBytes = 20_000))
        assertTrue("read $served bytes", served <= 20_000L + 2 * 8192)
    }

    @Test
    fun foreignContentUri_isAccepted() {
        assertTrue(isForeignContentUri("content", "com.android.providers.media.documents", OWN))
        // Shares our package as a prefix, but without the dot it is someone else's authority.
        assertTrue(isForeignContentUri("content", "de.pyryco.mobilefiles", OWN))
    }

    @Test
    fun nonContentSchemes_andMissingParts_areRefused() {
        assertFalse(isForeignContentUri("file", "", OWN))
        assertFalse(isForeignContentUri("file", null, OWN))
        assertFalse(isForeignContentUri("android.resource", OWN, OWN))
        assertFalse(isForeignContentUri("CONTENT", "com.other", OWN))
        assertFalse(isForeignContentUri(null, "com.other", OWN))
        assertFalse(isForeignContentUri("content", null, OWN))
        assertFalse(isForeignContentUri("content", "", OWN))
    }

    @Test
    fun ourOwnProviders_areRefused() {
        assertFalse(isForeignContentUri("content", OWN, OWN))
        assertFalse(isForeignContentUri("content", "$OWN.androidx-startup", OWN))
        assertFalse(isForeignContentUri("content", "$OWN.fileprovider", OWN))
    }

    @Test
    fun bytesResult_toStringRedactsTheContent() {
        val read = AttachmentRead.Bytes("secret".toByteArray())
        assertFalse(read.toString().contains("115")) // 's'
        assertFalse(read.toString().contains("secret"))
    }

    private companion object {
        const val OWN = "de.pyryco.mobile"
    }
}
