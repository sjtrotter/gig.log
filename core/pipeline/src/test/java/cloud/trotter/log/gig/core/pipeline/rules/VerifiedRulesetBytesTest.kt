package cloud.trotter.log.gig.core.pipeline.rules

import android.content.Context
import cloud.trotter.feed.contract.Feed
import cloud.trotter.feed.contract.FeedKeys
import cloud.trotter.feed.contract.FeedRefusal
import cloud.trotter.feed.contract.FeedResult
import cloud.trotter.feed.contract.FeedSources
import cloud.trotter.feed.contract.FeedTrust
import cloud.trotter.feed.contract.UserPin
import cloud.trotter.feed.contract.UserPinRejection
import cloud.trotter.feed.contract.VerifiedFeedFile
import cloud.trotter.feed.contract.VerifiedManifest
import cloud.trotter.log.gig.domain.capability.RuleCapabilityGrants
import cloud.trotter.log.gig.domain.model.accessibility.UiNode
import cloud.trotter.log.gig.domain.state.Platform
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import java.io.ByteArrayInputStream
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/**
 * #640: [VerifiedRulesetBytes.fromFeedFile] is the only remote mint. It takes a rules file from gig.log named for a
 * platform this build carries, and nothing else; the remote file it mints compiles and goes live through the loader.
 */
class VerifiedRulesetBytesTest {

    private val interpreter = JsonRuleInterpreter(mock<Context>(), mock<RuleCapabilityGrants>())

    private val bankingScreen = UiNode(children = listOf(UiNode(text = "Available Balance")))

    private val goodBundle = """
        { "format_version": 2, "platform_id": "doordash.driver", "screens": [
          { "id": "doordash.screen.sensitive.known", "priority": 0, "overrideable": false,
            "parse": { "as": "sensitive" }, "require": { "exists": { "hasText": "Available Balance" } } },
          { "id": "doordash.screen.idle_map", "priority": 10, "require": { "exists": { "hasText": "MapView" } } } ] }
    """.trimIndent()

    @Test fun `a gig_log rules file named for a platform mints and goes live`() = runTest {
        interpreter.loadBundled(listOf(TestBundledFloor.uber))
        val verified = TestRulesFeed.verified(goodBundle, sequence = TestRulesFeed.NOW)
        assertEquals(Platform.DoorDash, verified.platform)
        assertEquals("feed:gig.log/doordash", verified.source)
        assertEquals(TestRulesFeed.NOW, verified.sequence)
        assertTrue(!verified.source.startsWith(RuleCapabilityGrants.ASSET_SOURCE_PREFIX))
        interpreter.load(verified)
        assertEquals("sensitive", interpreter.screenRuleset?.matchFirst(bankingScreen)?.shape)
    }

    @Test fun `a file named for no platform, or for Unknown, is refused`() {
        for (name in listOf("acme", Platform.Unknown.wire)) {
            val file = TestRulesFeed.verifiedFiles(listOf(TestRulesFeed.File(name, goodBundle))).single()
            assertNull(name, VerifiedRulesetBytes.fromFeedFile(file))
        }
    }

    @Test fun `bytes that are not UTF-8 are refused`() {
        val file = TestRulesFeed.verifiedFiles(listOf(TestRulesFeed.File("doordash", byteArrayOf(0x7b, 0xC3.toByte(), 0x28, 0x7d)))).single()
        assertNull(VerifiedRulesetBytes.fromFeedFile(file))
    }

    @Test fun `a file of another feed is refused, even one a dasher's own source signed`() {
        val keys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val pin = UserPin(Feed.FUEL, Base64.getEncoder().encodeToString(keys.public.encoded))
        val trust = (FeedTrust.shipped(listOf(pin)) as FeedResult.Ok).value
        val source = FeedSources.custom(FeedKeys.keyId(keys.public.encoded))
        val payload = goodBundle.toByteArray()
        val manifest = TestRulesFeed.manifest(listOf(TestRulesFeed.File("prices", payload)), source = source, feed = Feed.FUEL)
        val sig = Signature.getInstance("SHA256withECDSA").run {
            initSign(keys.private)
            update(manifest.toByteArray())
            Base64.getEncoder().encodeToString(sign())
        }
        val envelope = """{"manifest":${JsonPrimitive(manifest)},"key":"${FeedKeys.keyId(keys.public.encoded)}","sig":"$sig"}"""
        val verified = (VerifiedManifest.verify(trust, source, Feed.FUEL, ByteArrayInputStream(envelope.toByteArray())) as FeedResult.Ok).value
        val file = (VerifiedFeedFile.verify(verified, "prices", ByteArrayInputStream(payload)) as FeedResult.Ok).value
        assertNull(VerifiedRulesetBytes.fromFeedFile(file))
    }

    @Test fun `no source but gig_log can serve rules`() {
        // A dasher's key is never filed for the rules feed, so a rules manifest under any other source id has no
        // trusted epoch and never becomes a VerifiedFeedFile.
        val keys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val pin = UserPin(Feed.RULES, Base64.getEncoder().encodeToString(keys.public.encoded))
        val trust = (FeedTrust.shipped(listOf(pin)) as FeedResult.Ok).value
        assertEquals(UserPinRejection.Reason.FEED_TAKES_NO_USER_PINS, trust.rejectedUserPins.single().reason)
        val source = FeedSources.custom(FeedKeys.keyId(keys.public.encoded))
        val manifest = TestRulesFeed.manifest(listOf(TestRulesFeed.File("doordash", goodBundle)), source = source)
        val result = VerifiedManifest.verify(trust, source, Feed.RULES, ByteArrayInputStream(TestRulesFeed.envelope(manifest)))
        assertEquals(FeedRefusal.NoTrustedEpoch, (result as FeedResult.Refused).reason)
    }

    @Test fun `a payload changed after signing never becomes a verified file`() {
        val file = TestRulesFeed.File("doordash", goodBundle)
        val manifest = TestRulesFeed.manifest(listOf(file))
        val verified = (VerifiedManifest.verify(TestRulesFeed.trust(), FeedSources.GIG_LOG, Feed.RULES,
            ByteArrayInputStream(TestRulesFeed.envelope(manifest))) as FeedResult.Ok).value
        val tampered = goodBundle.replaceFirst("Available Balance", "Available BalancX").toByteArray()
        assertEquals(FeedRefusal.HashMismatch,
            (VerifiedFeedFile.verify(verified, "doordash", ByteArrayInputStream(tampered)) as FeedResult.Refused).reason)
    }
}
