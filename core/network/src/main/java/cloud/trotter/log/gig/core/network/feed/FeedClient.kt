package cloud.trotter.log.gig.core.network.feed

import cloud.trotter.feed.contract.Feed
import cloud.trotter.feed.contract.FeedLimits
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume

/** Consent for one fetch (#1565: checked before every request). Closed or absent sends nothing. */
interface FeedGate {
    /** The full check, asked before every request, retries included. It may suspend. */
    suspend fun allows(): Boolean

    /** A non-blocking snapshot, read inside every network exchange on OkHttp's thread. It must not block. */
    fun isOpen(): Boolean
}

/** Why a feed request has no body. Never carries a URL, a header or server text. */
enum class FeedFetchFailure {
    /** The source's URL is not https; nothing was sent. */
    INSECURE_URL,

    /** A payload name that is not a sha256; nothing was sent. */
    MALFORMED_PATH,

    /** A 3xx: redirects are refused, never followed. */
    REDIRECT,

    /** A 404 after both retries: the file is not on that edge yet, or not published. */
    NOT_FOUND,

    /** Any other status, or a 5xx after both retries. */
    HTTP_ERROR,

    /** The body is larger than its cap. */
    TOO_LARGE,

    /** No answer: DNS, connection, TLS or a reset, after both retries. */
    TRANSPORT,
}

sealed interface FeedReply {
    /** [etag] is set for gig.log's own source only; a custom source's ETag is never handed back. */
    class Body(val bytes: ByteArray, val etag: String?) : FeedReply

    /** A 304 to gig.log's conditional request. It never renews freshness. */
    data object NotModified : FeedReply

    /** The gate said no before a request. */
    data object Denied : FeedReply

    data class Failed(val failure: FeedFetchFailure) : FeedReply
}

/**
 * The feed transport (ADR-0013, design-r3 § App client). It fetches bytes and checks nothing about them beyond their
 * size; the caller verifies them with `feed-contract`.
 *
 * - **https only, for every source.** The URL is checked before every request, and a payload URL is built from the
 *   signed sha256 under the envelope's own feed directory, so it cannot leave that origin.
 * - **Redirects off** (`followRedirects`, `followSslRedirects`), **no cookie jar, no cache**, and no header of ours
 *   but a fixed [USER_AGENT]: no account, install id, version or location.
 * - **`If-None-Match` only to gig.log's own source** (security X8): an operator-chosen ETag could follow a phone
 *   across IP addresses, so a custom source gets no conditional request and its ETag is dropped.
 * - **Two retries a minute apart** ([RETRY_DELAY_MS]) on a 404, a 408, a 429, a 5xx or a transport failure, which
 *   covers an edge cache that has the new envelope before its payloads. A redirect or any other status is final.
 * - [FeedGate.allows] is asked before every request, retries included, and [FeedGate.isOpen] inside every network
 *   exchange, so no follow-up OkHttp makes on its own runs without it.
 *
 * @param retryDelay waits before retry `n` (1 or 2); tests pass one that does not wait.
 */
class FeedClient(
    client: OkHttpClient,
    private val retryDelay: suspend (retry: Int) -> Unit = { delay(RETRY_DELAY_MS) },
) {
    private val client = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .cookieJar(CookieJar.NO_COOKIES)
        .cache(null)
        .retryOnConnectionFailure(false)
        .addNetworkInterceptor { chain ->
            // Every exchange reads the gate's snapshot (fail closed), so no follow-up OkHttp makes on its own skips it,
            // and Retry-After is dropped so OkHttp never replays a 503 itself: the only retries are this client's.
            val gate = chain.request().tag(FeedGate::class.java)
            if (gate == null || !gate.isOpen()) throw GateClosed()
            val response = chain.proceed(chain.request())
            if (response.header(RETRY_AFTER) == null) response else response.newBuilder().removeHeader(RETRY_AFTER).build()
        }
        .build()

    private class GateClosed : IOException("feed gate closed")

    /** `GET <feedsUrl><feed>/envelope.json`, conditional on [etag] only when [source] is gig.log's. */
    suspend fun envelope(source: FeedSource, feed: Feed, etag: String?, gate: FeedGate): FeedReply {
        val url = feedDir(source, feed)?.resolve(ENVELOPE) ?: return FeedReply.Failed(FeedFetchFailure.INSECURE_URL)
        val conditional = if (source is FeedSource.GigLog) etag else null
        return get(url, FeedLimits.MAX_ENVELOPE_BYTES, conditional, keepEtag = source is FeedSource.GigLog, gate)
    }

    /** `GET <feedsUrl><feed>/<sha256>.json`, at most [size] bytes. Never conditional. */
    suspend fun payload(source: FeedSource, feed: Feed, sha256: String, size: Int, gate: FeedGate): FeedReply {
        val dir = feedDir(source, feed) ?: return FeedReply.Failed(FeedFetchFailure.INSECURE_URL)
        if (!SHA256_HEX.matches(sha256)) return FeedReply.Failed(FeedFetchFailure.MALFORMED_PATH)
        val url = dir.resolve("$sha256.json") ?: return FeedReply.Failed(FeedFetchFailure.MALFORMED_PATH)
        return get(url, size, etag = null, keepEtag = false, gate)
    }

    private fun feedDir(source: FeedSource, feed: Feed): HttpUrl? {
        val base = source.feedsUrl.toHttpUrlOrNull()?.takeIf { it.isHttps } ?: return null
        return base.resolve("${feed.wire}/")?.takeIf { it.isHttps && it.host == base.host && it.port == base.port }
    }

    private suspend fun get(url: HttpUrl, cap: Int, etag: String?, keepEtag: Boolean, gate: FeedGate): FeedReply {
        var retry = 0
        while (true) {
            if (!gate.allows()) return FeedReply.Denied
            val attempt = once(url, cap, etag, keepEtag, gate)
            if (!attempt.retryable || retry == MAX_RETRIES) return attempt.reply
            retry++
            retryDelay(retry)
        }
    }

    private class Attempt(val reply: FeedReply, val retryable: Boolean)

    private suspend fun once(url: HttpUrl, cap: Int, etag: String?, keepEtag: Boolean, gate: FeedGate): Attempt {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).get().tag(FeedGate::class.java, gate)
            .apply { if (etag != null) header("If-None-Match", etag) }
            .build()
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    val reply = if (e is GateClosed) Attempt(FeedReply.Denied, retryable = false)
                    else Attempt(FeedReply.Failed(FeedFetchFailure.TRANSPORT), retryable = true)
                    continuation.resume(reply)
                }

                override fun onResponse(call: Call, response: Response) {
                    val attempt = try {
                        response.use { read(it, cap, keepEtag) }
                    } catch (_: IOException) {
                        Attempt(FeedReply.Failed(FeedFetchFailure.TRANSPORT), retryable = true)
                    }
                    continuation.resume(attempt)
                }
            })
        }
    }

    private fun read(response: Response, cap: Int, keepEtag: Boolean): Attempt = when (response.code) {
        200 -> {
            val declared = response.body.contentLength()
            val bytes = if (declared > cap) null else FeedLimits.readBounded(response.body.byteStream(), cap)
            if (bytes == null) failed(FeedFetchFailure.TOO_LARGE)
            else Attempt(FeedReply.Body(bytes, if (keepEtag) response.header("ETag") else null), retryable = false)
        }
        304 -> Attempt(if (keepEtag) FeedReply.NotModified else FeedReply.Failed(FeedFetchFailure.HTTP_ERROR), false)
        in 300..399 -> failed(FeedFetchFailure.REDIRECT)
        404 -> Attempt(FeedReply.Failed(FeedFetchFailure.NOT_FOUND), retryable = true)
        408, 429, in 500..599 -> Attempt(FeedReply.Failed(FeedFetchFailure.HTTP_ERROR), retryable = true)
        else -> failed(FeedFetchFailure.HTTP_ERROR)
    }

    private fun failed(failure: FeedFetchFailure) = Attempt(FeedReply.Failed(failure), retryable = false)

    companion object {
        /** Fixed and generic: no version, device or install in it. */
        const val USER_AGENT = "gig.log-feeds/1"
        const val RETRY_DELAY_MS = 60_000L
        const val MAX_RETRIES = 2
        private const val ENVELOPE = "envelope.json"
        private const val RETRY_AFTER = "Retry-After"
        private val SHA256_HEX = Regex("[0-9a-f]{64}")
    }
}
