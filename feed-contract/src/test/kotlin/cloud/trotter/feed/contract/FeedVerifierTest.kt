package cloud.trotter.feed.contract

import cloud.trotter.feed.contract.TestFeeds.Payload
import cloud.trotter.feed.contract.TestFeeds.envelope
import cloud.trotter.feed.contract.TestFeeds.keyPair
import cloud.trotter.feed.contract.TestFeeds.manifest
import cloud.trotter.feed.contract.TestFeeds.refusal
import cloud.trotter.feed.contract.TestFeeds.verified
import cloud.trotter.feed.contract.TestFeeds.verify
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class FeedVerifierTest {

    private val fuelPrimary = keyPair()
    private val fuelBackup = keyPair()
    private val rulesPrimary = keyPair()
    private val rulesBackup = keyPair()
    private val trust = TestFeeds.trust(
        Feed.FUEL to (fuelPrimary to fuelBackup),
        Feed.RULES to (rulesPrimary to rulesBackup),
    )

    private val prices = Payload("prices", """{"prices":{"US":{"regular":3.05}}}""".toByteArray())

    @Test
    fun `a signed envelope verifies and its files verify against it`() {
        val manifestText = manifest(Feed.FUEL, listOf(prices))
        val m = verified(trust, Feed.FUEL, envelope(manifestText, fuelPrimary))
        assertEquals(FeedSources.GIG_LOG, m.source)
        assertEquals(Feed.FUEL, m.feed)
        assertEquals(TestFeeds.keyId(fuelPrimary), m.keyId)
        assertEquals(FeedKeys.sha256Hex(manifestText.toByteArray()), m.digest)
        assertEquals(TestFeeds.NOW, m.candidate.sequence)

        val file = (FeedVerifier.verifyFile(m, "prices", ByteArrayInputStream(prices.bytes)) as FeedResult.Ok).value
        assertEquals(Feed.FUEL, file.feed)
        assertEquals(FeedSources.GIG_LOG, file.sourceId)
        assertArrayEquals(prices.bytes, file.bytes())
        // Immutable: changing the returned copy changes nothing.
        file.bytes()[0] = 0
        assertArrayEquals(prices.bytes, file.bytes())
    }

    @Test
    fun `the backup key of the epoch verifies too`() {
        val m = verified(trust, Feed.FUEL, envelope(manifest(Feed.FUEL, listOf(prices)), fuelBackup))
        assertEquals(TestFeeds.keyId(fuelBackup), m.keyId)
    }

    @Test
    fun `a changed manifest or a foreign key is refused`() {
        val good = String(envelope(manifest(Feed.FUEL, listOf(prices)), fuelPrimary))
        val tampered = good.replace("\\\"contract\\\":1", "\\\"contract\\\":2")
        assertTrue(tampered != good)
        assertEquals(FeedRefusal.BadSignature, refusal(verify(trust, Feed.FUEL, tampered.toByteArray())))

        val stranger = keyPair()
        val unpinned = envelope(manifest(Feed.FUEL, listOf(prices)), stranger)
        assertEquals(FeedRefusal.UnknownKey, refusal(verify(trust, Feed.FUEL, unpinned)))
        // Naming a pinned key id does not help a signature made with another key.
        val lying = envelope(manifest(Feed.FUEL, listOf(prices)), stranger, keyId = TestFeeds.keyId(fuelPrimary))
        assertEquals(FeedRefusal.BadSignature, refusal(verify(trust, Feed.FUEL, lying)))
    }

    @Test
    fun `a fuel file offered as rules is refused`() {
        val fuelEnvelope = envelope(manifest(Feed.FUEL, listOf(prices)), fuelPrimary)
        // The fuel key is not in the rules epoch.
        assertEquals(FeedRefusal.UnknownKey, refusal(verify(trust, Feed.RULES, fuelEnvelope)))
        // Even a key pinned for both feeds cannot carry a fuel manifest into the rules feed: the signed feed must equal
        // the request.
        val shared = keyPair()
        val both = TestFeeds.trust(Feed.FUEL to (shared to keyPair()), Feed.RULES to (shared to keyPair()))
        val sharedFuel = envelope(manifest(Feed.FUEL, listOf(prices)), shared)
        assertEquals(FeedRefusal.WrongFeed, refusal(verify(both, Feed.RULES, sharedFuel)))
        // And a verified fuel file says it is fuel.
        val m = verified(both, Feed.FUEL, sharedFuel)
        val file = (FeedVerifier.verifyFile(m, "prices", ByteArrayInputStream(prices.bytes)) as FeedResult.Ok).value
        assertEquals(Feed.FUEL, file.feed)
    }

    @Test
    fun `a custom source verifies only under its own id`() {
        val custom = keyPair()
        val withPin = TestFeeds.trust(
            Feed.FUEL to (fuelPrimary to fuelBackup),
            userPins = listOf(UserPin(Feed.FUEL, TestFeeds.publicB64(custom))),
        )
        val customId = FeedSources.custom(TestFeeds.keyId(custom))
        val signed = envelope(manifest(Feed.FUEL, listOf(prices), source = customId), custom)
        assertEquals(customId, verified(withPin, Feed.FUEL, signed, source = customId).source)
        // Asked as gig.log, the custom key is not gig.log's.
        assertEquals(FeedRefusal.UnknownKey, refusal(verify(withPin, Feed.FUEL, signed)))
        // A custom key that writes "gig.log" into its manifest is refused under its own id.
        val claimsGigLog = envelope(manifest(Feed.FUEL, listOf(prices)), custom)
        assertEquals(FeedRefusal.WrongSource, refusal(verify(withPin, Feed.FUEL, claimsGigLog, source = customId)))
    }

    @Test
    fun `no epoch for the source and feed is refused`() {
        val constants = envelope(manifest(Feed.CONSTANTS, listOf(Payload("values", "{}".toByteArray()))), fuelPrimary)
        assertEquals(FeedRefusal.NoTrustedEpoch, refusal(verify(trust, Feed.CONSTANTS, constants)))
    }

    @Test
    fun `unknown top-level keys are ignored and an unknown format_version is refused`() {
        val withAdvisory = manifest(Feed.FUEL, listOf(prices), extra = ""","advisory":{"level":1},"later":[1,2]""")
        assertTrue(verify(trust, Feed.FUEL, envelope(withAdvisory, fuelPrimary)) is FeedResult.Ok)
        val v2 = manifest(Feed.FUEL, listOf(prices), formatVersion = 2)
        assertEquals(FeedRefusal.UnsupportedFormatVersion, refusal(verify(trust, Feed.FUEL, envelope(v2, fuelPrimary))))
        val noVersion = manifest(Feed.FUEL, listOf(prices)).replace("\"format_version\":1,", "")
        assertEquals(
            FeedRefusal.Malformed("format_version"),
            refusal(verify(trust, Feed.FUEL, envelope(noVersion, fuelPrimary))),
        )
    }

    @Test
    fun `duplicate keys and depth are refused in the envelope and in the signed manifest`() {
        val dupInManifest = manifest(Feed.FUEL, listOf(prices)).replace("\"contract\":1", "\"contract\":1,\"contract\":2")
        assertEquals(FeedRefusal.DuplicateKey, refusal(verify(trust, Feed.FUEL, envelope(dupInManifest, fuelPrimary))))
        val deep = manifest(Feed.FUEL, listOf(prices), extra = ""","advisory":{"a":{"b":{"c":{"d":1}}}}""")
        assertEquals(FeedRefusal.TooDeep, refusal(verify(trust, Feed.FUEL, envelope(deep, fuelPrimary))))
        val good = String(envelope(manifest(Feed.FUEL, listOf(prices)), fuelPrimary))
        val dupEnvelope = good.replaceFirst("{\"manifest\"", "{\"key\":\"x\",\"manifest\"")
        assertEquals(FeedRefusal.DuplicateKey, refusal(verify(trust, Feed.FUEL, dupEnvelope.toByteArray())))
    }

    @Test
    fun `byte caps hold on the envelope and on the signed sizes`() {
        val huge = ByteArray(FeedLimits.MAX_ENVELOPE_BYTES + 1) { ' '.code.toByte() }
        assertEquals(FeedRefusal.TooLarge, refusal(verify(trust, Feed.FUEL, huge)))

        val tooBig = Payload("prices", ByteArray(FeedLimits.MAX_FUEL_BYTES + 1))
        assertEquals(
            FeedRefusal.FileTooLarge,
            refusal(verify(trust, Feed.FUEL, envelope(manifest(Feed.FUEL, listOf(tooBig)), fuelPrimary))),
        )
        val fiveRules = (1..5).map { Payload("p$it", ByteArray(FeedLimits.MAX_RULES_FILE_BYTES)) }
        assertEquals(
            FeedRefusal.GenerationTooLarge,
            refusal(verify(trust, Feed.RULES, envelope(manifest(Feed.RULES, fiveRules), rulesPrimary))),
        )
        val fourRules = fiveRules.take(4)
        assertTrue(verify(trust, Feed.RULES, envelope(manifest(Feed.RULES, fourRules), rulesPrimary)) is FeedResult.Ok)
    }

    @Test
    fun `a file must match its signed size and sha256 before it is returned`() {
        val m = verified(trust, Feed.FUEL, envelope(manifest(Feed.FUEL, listOf(prices)), fuelPrimary))
        fun check(name: String, bytes: ByteArray) = refusal(FeedVerifier.verifyFile(m, name, ByteArrayInputStream(bytes)))
        assertEquals(FeedRefusal.FileNotInManifest, check("values", prices.bytes))
        assertEquals(FeedRefusal.SizeMismatch, check("prices", prices.bytes + byteArrayOf(1)))
        assertEquals(FeedRefusal.SizeMismatch, check("prices", prices.bytes.copyOf(prices.bytes.size - 1)))
        val flipped = prices.bytes.copyOf().also { it[3] = (it[3] + 1).toByte() }
        assertEquals(FeedRefusal.HashMismatch, check("prices", flipped))
    }

    @Test
    fun `file entries are strict`() {
        val base = manifest(Feed.FUEL, listOf(prices))
        val extraKey = base.replace("\"size\":", "\"url\":\"x\",\"size\":")
        assertEquals(FeedRefusal.Malformed("files"), refusal(verify(trust, Feed.FUEL, envelope(extraKey, fuelPrimary))))
        val upperHash = base.replace(prices.sha256, prices.sha256.uppercase())
        assertEquals(FeedRefusal.Malformed("sha256"), refusal(verify(trust, Feed.FUEL, envelope(upperHash, fuelPrimary))))
        val dup = manifest(Feed.FUEL, listOf(prices, prices))
        assertEquals(FeedRefusal.DuplicateFileName, refusal(verify(trust, Feed.FUEL, envelope(dup, fuelPrimary))))
        val many = manifest(Feed.RULES, (0..FeedLimits.MAX_FILES_PER_MANIFEST).map { Payload("p$it", ByteArray(1)) })
        assertEquals(FeedRefusal.TooManyFiles, refusal(verify(trust, Feed.RULES, envelope(many, rulesPrimary))))
        val fractional = base.replace("\"contract\":1", "\"contract\":1.0")
        assertEquals(FeedRefusal.Malformed("contract"), refusal(verify(trust, Feed.FUEL, envelope(fractional, fuelPrimary))))
    }

    @Test
    fun `file names come from the feed or the caller's resolver`() {
        val fuel = verified(
            trust, Feed.FUEL,
            envelope(manifest(Feed.FUEL, listOf(prices, Payload("extra", "{}".toByteArray()))), fuelPrimary),
        )
        assertEquals(listOf("prices"), FeedFiles.select(fuel) { true }.map { it.name })

        val rules = verified(
            trust, Feed.RULES,
            envelope(manifest(Feed.RULES, listOf("alpha", "beta", "gamma").map { Payload(it, "{}".toByteArray()) }), rulesPrimary),
        )
        val known = setOf("alpha", "gamma")
        assertEquals(listOf("alpha", "gamma"), FeedFiles.select(rules) { it in known }.map { it.name })
    }

    @Test
    fun `fuel and constants payloads take the depth-4 pre-pass and rules payloads go to the compiler`() {
        val m = verified(trust, Feed.FUEL, envelope(manifest(Feed.FUEL, listOf(prices)), fuelPrimary))
        val file = (FeedVerifier.verifyFile(m, "prices", ByteArrayInputStream(prices.bytes)) as FeedResult.Ok).value
        assertTrue((FeedFiles.parsePayload(file) as FeedResult.Ok).value is JsonObject)

        val deep = Payload("prices", """{"a":{"b":{"c":{"d":{"e":1}}}}}""".toByteArray())
        val deepM = verified(trust, Feed.FUEL, envelope(manifest(Feed.FUEL, listOf(deep)), fuelPrimary))
        val deepFile = (FeedVerifier.verifyFile(deepM, "prices", ByteArrayInputStream(deep.bytes)) as FeedResult.Ok).value
        assertEquals(FeedRefusal.TooDeep, refusal(FeedFiles.parsePayload(deepFile)))

        val rule = Payload("alpha", """{"a":{"b":{"c":{"d":{"e":1}}}}}""".toByteArray())
        val rulesM = verified(trust, Feed.RULES, envelope(manifest(Feed.RULES, listOf(rule)), rulesPrimary))
        val ruleFile = (FeedVerifier.verifyFile(rulesM, "alpha", ByteArrayInputStream(rule.bytes)) as FeedResult.Ok).value
        assertEquals(FeedRefusal.WrongFeed, refusal(FeedFiles.parsePayload(ruleFile)))
    }
}
