package cloud.trotter.log.gig.core.data.feed

import cloud.trotter.feed.contract.Feed
import cloud.trotter.feed.contract.FeedFiles
import cloud.trotter.feed.contract.FeedKeys
import cloud.trotter.feed.contract.FeedResult
import cloud.trotter.feed.contract.UserPin
import cloud.trotter.feed.contract.VerifiedFeedFile
import cloud.trotter.log.gig.core.network.feed.FeedSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import okio.Buffer
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 2026-11-15T00:00:00Z. */
const val NOW = 1_794_700_800_000L
const val HOUR = 60L * 60 * 1000

/** A custom source for [feed] signed by a generated key: the public path a dasher's own feed takes (no reflection). */
class SignedSource(val feed: Feed, url: String = FeedTestServer.CUSTOM_FEEDS_URL) {
    private val keys: KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
        .generateKeyPair()
    private val publicB64 = Base64.getEncoder().encodeToString(keys.public.encoded)
    val source: FeedSource.Custom = requireNotNull(FeedSource.Custom.of(url, UserPin(feed, publicB64)))

    /** The same key at another URL: the same source id, so the same history. */
    fun at(url: String): FeedSource.Custom = requireNotNull(FeedSource.Custom.of(url, UserPin(feed, publicB64)))

    class Generation(val sequence: Long, val envelope: ByteArray, val payload: ByteArray) {
        val sha256: String = FeedKeys.sha256Hex(payload)
    }

    /** A generation of one payload file whose body is the test stager's `{"v":…, "period":…}`. */
    fun generation(sequence: Long, value: String, period: Long = sequence - 7 * 24 * HOUR): Generation {
        val payload = """{"v":"$value","period":$period}""".toByteArray()
        val manifest = """{"format_version":1,"source":"${source.id}","feed":"${feed.wire}","sequence":$sequence,""" +
            """"issued_at":$sequence,"contract":1,"files":[{"name":"${feed.fileName}","sha256":"${FeedKeys.sha256Hex(payload)}",""" +
            """"size":${payload.size}}]}"""
        val sig = Signature.getInstance("SHA256withECDSA").run {
            initSign(keys.private)
            update(manifest.toByteArray(Charsets.UTF_8))
            Base64.getEncoder().encodeToString(sign())
        }
        val keyId = FeedKeys.keyId(keys.public.encoded)
        val envelope = """{"manifest":${JsonPrimitive(manifest)},"key":"$keyId","sig":"$sig"}""".toByteArray()
        return Generation(sequence, envelope, payload)
    }
}

/**
 * Serves what a feed site holds: the envelope at `/feeds/<feed>/envelope.json` and each payload at its sha256. Counts
 * every request by path; [hold] blocks one path's answer until [release].
 */
class FeedSite : Dispatcher() {
    @Volatile var envelope: ByteArray? = null
    private val payloads = ConcurrentHashMap<String, ByteArray>()
    val requests = CopyOnWriteArrayList<RecordedRequest>()
    @Volatile var envelopeHeaders: List<Pair<String, String>> = emptyList()
    @Volatile var redirect = false
    @Volatile private var held: String? = null
    private val heldReached = CountDownLatch(1)
    private val release = CountDownLatch(1)

    fun publish(feed: Feed, generation: SignedSource.Generation) {
        envelope = generation.envelope
        payloads["/feeds/${feed.wire}/${generation.sha256}.json"] = generation.payload
    }

    fun hold(path: String) { held = path }
    fun awaitHeld(): Boolean = heldReached.await(5, TimeUnit.SECONDS)
    fun release() = release.countDown()

    override fun dispatch(request: RecordedRequest): MockResponse {
        requests += request
        val path = request.url.encodedPath
        if (path == held) {
            heldReached.countDown()
            release.await(10, TimeUnit.SECONDS)
        }
        if (redirect) return MockResponse.Builder().code(302).addHeader("Location", "https://${FeedTestServer.CUSTOM_HOST}/x").build()
        val body = if (path.endsWith("/envelope.json")) envelope else payloads[path]
        body ?: return MockResponse.Builder().code(404).build()
        val builder = MockResponse.Builder().code(200).body(Buffer().write(body))
        if (path.endsWith("/envelope.json")) envelopeHeaders.forEach { builder.addHeader(it.first, it.second) }
        return builder.build()
    }
}

/** Thrown from a crash point: the process dies there, so nothing after it runs. Not an Exception, so no catch eats it. */
class SimulatedDeath : Throwable()

class FakeFeedPolicy(enabled: Boolean, source: FeedSource) : FeedPolicy {
    val enabled = MutableStateFlow(enabled)
    val source = MutableStateFlow(source)
    override fun enabled(feed: Feed): StateFlow<Boolean> = enabled
    override fun source(feed: Feed): StateFlow<FeedSource> = source
}

/**
 * Reads the test payload `{"v": text, "period": ms}`. A value of "bad" is refused. The carry
 * is the list of every value accepted so far, so a test can see it travel through `meta.json`.
 */
class TestStager : FeedStager<String> {
    override val contracts: Set<Long> = setOf(1L)

    override fun stage(files: List<VerifiedFeedFile>, carry: JsonObject?): FeedStage<String> {
        val (value, period) = read(files) ?: return FeedStage.Rejected("unreadable")
        if (value == "bad") return FeedStage.Rejected("out_of_bounds")
        val seen = (carry?.get("seen") as? JsonArray).orEmpty() + JsonPrimitive(value)
        return FeedStage.Ready(value, period, JsonObject(mapOf("seen" to JsonArray(seen))))
    }

    override fun restore(files: List<VerifiedFeedFile>, carry: JsonObject?): FeedStage<String> {
        val (value, period) = read(files) ?: return FeedStage.Rejected("unreadable")
        return FeedStage.Ready(value, period, carry)
    }

    private fun read(files: List<VerifiedFeedFile>): Pair<String, Long>? {
        val file = files.singleOrNull() ?: return null
        val tree = (FeedFiles.parsePayload(file) as? FeedResult.Ok)?.value?.jsonObject ?: return null
        val value = tree["v"]?.jsonPrimitive?.content ?: return null
        return value to (tree["period"]?.jsonPrimitive?.long ?: return null)
    }
}
