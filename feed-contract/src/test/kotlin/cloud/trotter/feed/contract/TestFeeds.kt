package cloud.trotter.feed.contract

import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayInputStream
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/** Keys, manifests and envelopes the tests build for themselves. No real key appears in a test. */
internal object TestFeeds {
    /** 2026-11-15T00:00:00Z. */
    const val NOW = 1_794_700_800_000L
    const val HOUR = 60L * 60 * 1000
    const val DAY = 24 * HOUR

    fun keyPair(curve: String = "secp256r1"): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec(curve)) }.generateKeyPair()

    fun publicB64(pair: KeyPair): String = Base64.getEncoder().encodeToString(pair.public.encoded)

    fun keyId(pair: KeyPair): String = FeedKeys.keyId(pair.public.encoded)

    fun sign(pair: KeyPair, bytes: ByteArray): String = Signature.getInstance("SHA256withECDSA").run {
        initSign(pair.private)
        update(bytes)
        Base64.getEncoder().encodeToString(sign())
    }

    fun pinFile(vararg epochs: Pair<Feed, Pair<KeyPair, KeyPair>>): ByteArray {
        val feeds = epochs.joinToString(",") { (feed, keys) ->
            """"${feed.wire}":{"epoch":1,"primary":"${publicB64(keys.first)}","backup":"${publicB64(keys.second)}"}"""
        }
        return """{"format_version":1,"feeds":{$feeds}}""".toByteArray()
    }

    fun trust(vararg epochs: Pair<Feed, Pair<KeyPair, KeyPair>>, userPins: List<UserPin> = emptyList()): FeedTrust =
        (FeedTrust.fromPinFile(pinFile(*epochs), userPins) as FeedResult.Ok).value

    class Payload(val name: String, val bytes: ByteArray) {
        val sha256: String get() = FeedKeys.sha256Hex(bytes)
    }

    fun manifest(
        feed: Feed,
        files: List<Payload>,
        source: String = FeedSources.GIG_LOG,
        sequence: Long = NOW,
        issuedAt: Long = sequence,
        contract: Long = 1,
        formatVersion: Long = 1,
        extra: String = "",
    ): String {
        val list = files.joinToString(",") { """{"name":"${it.name}","sha256":"${it.sha256}","size":${it.bytes.size}}""" }
        return """{"format_version":$formatVersion,"source":"$source","feed":"${feed.wire}","sequence":$sequence,""" +
            """"issued_at":$issuedAt,"contract":$contract,"files":[$list]$extra}"""
    }

    fun envelope(manifest: String, signer: KeyPair, keyId: String = keyId(signer)): ByteArray {
        val sig = sign(signer, manifest.toByteArray(Charsets.UTF_8))
        return """{"manifest":${JsonPrimitive(manifest)},"key":"$keyId","sig":"$sig"}""".toByteArray(Charsets.UTF_8)
    }

    fun verify(trust: FeedTrust, feed: Feed, envelope: ByteArray, source: String = FeedSources.GIG_LOG) =
        FeedVerifier.verifyEnvelope(trust, source, feed, ByteArrayInputStream(envelope))

    fun verified(trust: FeedTrust, feed: Feed, envelope: ByteArray, source: String = FeedSources.GIG_LOG): VerifiedManifest =
        (verify(trust, feed, envelope, source) as FeedResult.Ok).value

    fun refusal(result: FeedResult<*>): FeedRefusal? = (result as? FeedResult.Refused)?.reason
}
