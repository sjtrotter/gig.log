package cloud.trotter.feed.contract

import cloud.trotter.feed.contract.TestFeeds.Payload
import cloud.trotter.feed.contract.TestFeeds.envelope
import cloud.trotter.feed.contract.TestFeeds.keyPair
import cloud.trotter.feed.contract.TestFeeds.manifest
import cloud.trotter.feed.contract.TestFeeds.refusal
import cloud.trotter.feed.contract.TestFeeds.verified
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class FeedBoundaryTest {

    private val primary = keyPair()
    private val trust = TestFeeds.trust(Feed.RULES to (primary to keyPair()))

    @Test
    fun `nothing a verified manifest exposes can change what verifyFile checks`() {
        val signed = listOf(Payload("alpha", "{}".toByteArray()), Payload("beta", "[]".toByteArray()))
        val m = verified(trust, Feed.RULES, envelope(manifest(Feed.RULES, signed), primary))
        val unsigned = ByteArray(FeedLimits.MAX_RULES_FILE_BYTES + 1)
        try {
            @Suppress("UNCHECKED_CAST")
            (m.files as MutableList<ManifestFile>)[0] = ManifestFile("alpha", FeedKeys.sha256Hex(unsigned), unsigned.size)
        } catch (_: UnsupportedOperationException) {
            // The list is read-only: the mutation never happened.
        }
        assertEquals(FeedRefusal.SizeMismatch, refusal(VerifiedFeedFile.verify(m, "alpha", ByteArrayInputStream(unsigned))))
        assertEquals(signed[0].sha256, m.files[0].sha256)
    }

    /** Android API 30-32 has no `InputStream.readNBytes`; these overrides stand in for the missing method. */
    private class Api30Stream(private val source: InputStream) : InputStream() {
        var consumed = 0
        override fun read(): Int = source.read().also { if (it >= 0) consumed++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int = source.read(b, off, len).also { if (it > 0) consumed += it }
        override fun readNBytes(len: Int): ByteArray = throw NoSuchMethodError("readNBytes(int) is API 33")
        override fun readNBytes(b: ByteArray, off: Int, len: Int): Int = throw NoSuchMethodError("readNBytes is API 33")
        override fun readAllBytes(): ByteArray = throw NoSuchMethodError("readAllBytes is API 33")
    }

    @Test
    fun `the bounded read uses only API 30 calls and stops at cap + 1`() {
        val small = Api30Stream(ByteArrayInputStream(ByteArray(10) { it.toByte() }))
        assertArrayEquals(ByteArray(10) { it.toByte() }, FeedLimits.readBounded(small, 10))

        val endless = Api30Stream(object : InputStream() {
            override fun read(): Int = 7
        })
        assertNull(FeedLimits.readBounded(endless, 100))
        assertTrue("read ${endless.consumed} bytes", endless.consumed <= 101)
    }
}
