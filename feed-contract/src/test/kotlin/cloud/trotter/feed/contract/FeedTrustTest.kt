package cloud.trotter.feed.contract

import cloud.trotter.feed.contract.TestFeeds.keyPair
import cloud.trotter.feed.contract.TestFeeds.publicB64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.Base64

class FeedTrustTest {

    @Test
    fun `the shipped pin file reads and pins gig_log's fuel key only`() {
        val trust = (FeedTrust.shipped() as FeedResult.Ok).value
        for (feed in Feed.entries) assertEquals("$feed", feed == Feed.FUEL, trust.trusts(FeedSources.GIG_LOG, feed))
        // The classpath copy is the repository file workflows read: one file, no second copy.
        val repoFile = File("pins/feed-pins.json")
        assertTrue("run from the feed-contract build dir", repoFile.isFile)
        val resource = FeedTrust::class.java.getResourceAsStream(FeedTrust.PIN_FILE_RESOURCE)!!.use { it.readBytes() }
        assertTrue(repoFile.readBytes().contentEquals(resource))
    }

    @Test
    fun `a key id is the first 16 hex of sha256 over the DER public key`() {
        val pair = keyPair()
        val der = pair.public.encoded
        val expected = MessageDigest.getInstance("SHA-256").digest(der)
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }.take(16)
        assertEquals(expected, FeedKeys.keyId(der))
        assertEquals(FeedSources.custom(expected), FeedKeys.customSourceId(publicB64(pair)))
    }

    @Test
    fun `a user pin is filed under custom key id and never under gig_log`() {
        val pin = keyPair()
        val trust = TestFeeds.trust(userPins = listOf(UserPin(Feed.FUEL, publicB64(pin))))
        val id = FeedSources.custom(TestFeeds.keyId(pin))
        assertTrue(trust.trusts(id, Feed.FUEL))
        assertTrue(id.startsWith(FeedSources.CUSTOM_PREFIX))
        assertNotEquals(FeedSources.GIG_LOG, id)
        assertFalse(trust.trusts(FeedSources.GIG_LOG, Feed.FUEL))
        assertFalse("a fuel pin trusts nothing else", trust.trusts(id, Feed.CONSTANTS))
    }

    @Test
    fun `a user pin for the rules feed is never filed`() {
        val pin = UserPin(Feed.RULES, publicB64(keyPair()))
        val trust = TestFeeds.trust(userPins = listOf(pin))
        assertEquals(listOf(UserPinRejection(pin, UserPinRejection.Reason.FEED_TAKES_NO_USER_PINS)), trust.rejectedUserPins)
        assertFalse(trust.trusts(FeedKeys.customSourceId(pin.publicKeyBase64Der)!!, Feed.RULES))
    }

    @Test
    fun `only canonical P-256 keys are pinned`() {
        val p384 = UserPin(Feed.FUEL, publicB64(keyPair("secp384r1")))
        val junk = UserPin(Feed.FUEL, "not base64!")
        val again = keyPair()
        val first = UserPin(Feed.FUEL, publicB64(again))
        val trust = TestFeeds.trust(userPins = listOf(p384, junk, first, first))
        assertEquals(
            listOf(
                UserPinRejection(p384, UserPinRejection.Reason.NOT_A_P256_KEY),
                UserPinRejection(junk, UserPinRejection.Reason.NOT_A_P256_KEY),
                UserPinRejection(first, UserPinRejection.Reason.DUPLICATE),
            ),
            trust.rejectedUserPins,
        )
        // DER that is not the key's canonical encoding is refused, so one key has one id.
        val padded = Base64.getEncoder().encodeToString(again.public.encoded + byteArrayOf(0))
        assertNull(FeedKeys.customSourceId(padded))
    }

    @Test
    fun `a custom backup key must be a different P-256 key`() {
        val primary = publicB64(keyPair())
        val same = UserPin(Feed.FUEL, primary, primary)
        val badBackup = UserPin(Feed.CONSTANTS, primary, publicB64(keyPair("secp384r1")))
        val trust = TestFeeds.trust(userPins = listOf(same, badBackup))
        assertEquals(
            listOf(
                UserPinRejection(same, UserPinRejection.Reason.SAME_KEY_TWICE),
                UserPinRejection(badBackup, UserPinRejection.Reason.NOT_A_P256_KEY),
            ),
            trust.rejectedUserPins,
        )
    }

    @Test
    fun `the pin file is strict`() {
        fun read(text: String) = TestFeeds.refusal(TestFeeds.fromPinFile(text.toByteArray(), emptyList()))
        val a = publicB64(keyPair())
        val b = publicB64(keyPair())
        assertNull(read("""{"format_version":1,"feeds":{"fuel":{"epoch":1,"primary":"$a","backup":"$b"}}}"""))
        assertEquals(
            "one epoch per feed",
            FeedRefusal.DuplicateKey,
            read("""{"format_version":1,"feeds":{"fuel":{"epoch":1,"primary":"$a","backup":"$b"},"fuel":{"epoch":2,"primary":"$b","backup":"$a"}}}"""),
        )
        assertEquals(FeedRefusal.UnsupportedFormatVersion, read("""{"format_version":2,"feeds":{}}"""))
        assertEquals(FeedRefusal.Malformed("feed"), read("""{"format_version":1,"feeds":{"tolls":{}}}"""))
        assertEquals(
            FeedRefusal.Malformed("backup"),
            read("""{"format_version":1,"feeds":{"fuel":{"epoch":1,"primary":"$a"}}}"""),
        )
        assertEquals(
            FeedRefusal.Malformed("backup"),
            read("""{"format_version":1,"feeds":{"fuel":{"epoch":1,"primary":"$a","backup":"$a"}}}"""),
        )
    }
}
