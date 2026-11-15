package cloud.trotter.log.gig.core.pipeline.rules

import cloud.trotter.feed.contract.Feed
import cloud.trotter.feed.contract.FeedKeys
import cloud.trotter.feed.contract.FeedResult
import cloud.trotter.feed.contract.FeedSources
import cloud.trotter.feed.contract.FeedTrust
import cloud.trotter.feed.contract.VerifiedFeedFile
import cloud.trotter.feed.contract.VerifiedManifest
import cloud.trotter.log.gig.domain.pipeline.RuleEngineConstants
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayInputStream
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/**
 * Signed rules feeds for tests, through the real verifier path (#640): the manifest is signed with the test-only gig.log
 * rules key the build generates (root `feedTestPins`, never committed), verified by [VerifiedManifest.verify] against
 * [FeedTrust.shipped], whose pin file on the unit-test classpath is that generated one, then minted by
 * [VerifiedRulesetBytes.fromFeedFile]. Nothing here takes a key around the pins.
 */
object TestRulesFeed {
    /** 2026-11-15T00:00:00Z. */
    const val NOW = 1_794_700_800_000L

    val primary: PrivateKey by lazy { privateKey("rules-primary") }
    val backup: PrivateKey by lazy { privateKey("rules-backup") }

    private fun privateKey(name: String): PrivateKey {
        val text = requireNotNull(TestRulesFeed::class.java.getResourceAsStream("/feed-test-keys/$name.pk8")) {
            "the generated test pin (root task feedTestPins) is not on the test classpath"
        }.use { it.readBytes().toString(Charsets.UTF_8) }
        return KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(text.trim())))
    }

    /** The key id of the test pin's primary key, read from the pin file the verifier reads. */
    val primaryKeyId: String by lazy { keyIdOf("primary") }

    private fun keyIdOf(slot: String): String {
        val pins = requireNotNull(TestRulesFeed::class.java.getResourceAsStream(FeedTrust.PIN_FILE_RESOURCE)).use {
            it.readBytes().toString(Charsets.UTF_8)
        }
        val b64 = Regex("\"$slot\":\"([^\"]+)\"").find(pins)!!.groupValues[1]
        return FeedKeys.keyId(Base64.getDecoder().decode(b64))
    }

    class File(val name: String, val bytes: ByteArray) {
        constructor(name: String, json: String) : this(name, json.toByteArray(Charsets.UTF_8))

        val sha256: String get() = FeedKeys.sha256Hex(bytes)
    }

    fun manifest(
        files: List<File>,
        sequence: Long = NOW,
        source: String = FeedSources.GIG_LOG,
        feed: Feed = Feed.RULES,
        contract: Long = RuleEngineConstants.RULES_CONTRACT.toLong(),
    ): String {
        val list = files.joinToString(",") { """{"name":"${it.name}","sha256":"${it.sha256}","size":${it.bytes.size}}""" }
        return """{"format_version":1,"source":"$source","feed":"${feed.wire}","sequence":$sequence,""" +
            """"issued_at":$sequence,"contract":$contract,"files":[$list]}"""
    }

    fun envelope(manifest: String, key: PrivateKey = primary, keyId: String = primaryKeyId): ByteArray {
        val sig = Signature.getInstance("SHA256withECDSA").run {
            initSign(key)
            update(manifest.toByteArray(Charsets.UTF_8))
            Base64.getEncoder().encodeToString(sign())
        }
        return """{"manifest":${JsonPrimitive(manifest)},"key":"$keyId","sig":"$sig"}""".toByteArray(Charsets.UTF_8)
    }

    fun trust(): FeedTrust = (FeedTrust.shipped() as FeedResult.Ok).value

    /** Every file of a gig.log rules generation, verified through the feed contract. */
    fun verifiedFiles(files: List<File>, sequence: Long = NOW): List<VerifiedFeedFile> {
        val manifest = manifest(files, sequence)
        val verified = VerifiedManifest.verify(trust(), FeedSources.GIG_LOG, Feed.RULES, ByteArrayInputStream(envelope(manifest)))
        val ok = (verified as? FeedResult.Ok)?.value ?: error("test rules manifest refused: $verified")
        return files.map { (VerifiedFeedFile.verify(ok, it.name, ByteArrayInputStream(it.bytes)) as FeedResult.Ok).value }
    }

    /** One rules file for [platformWire], minted through [VerifiedRulesetBytes.fromFeedFile]. */
    fun verified(json: String, platformWire: String = "doordash", sequence: Long = NOW): VerifiedRulesetBytes =
        requireNotNull(VerifiedRulesetBytes.fromFeedFile(verifiedFiles(listOf(File(platformWire, json)), sequence).single())) {
            "test rules file was refused"
        }
}
