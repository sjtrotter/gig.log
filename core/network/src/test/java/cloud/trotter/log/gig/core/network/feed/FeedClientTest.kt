package cloud.trotter.log.gig.core.network.feed

import cloud.trotter.feed.contract.Feed
import cloud.trotter.feed.contract.UserPin
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.concurrent.TimeUnit

class FeedClientTest {
    private val fake = FeedTestServer()
    private val server = fake.server
    private val delays = mutableListOf<Int>()
    private val client = FeedClient(fake.client) { retry -> delays += retry }
    private val open = FeedGate { true }
    private val sha = "ab".repeat(32)

    @After fun tearDown() = fake.close()

    private fun respond(code: Int, body: String = "", vararg headers: Pair<String, String>) {
        server.enqueue(MockResponse.Builder().code(code).body(body).apply { headers.forEach { addHeader(it.first, it.second) } }.build())
    }

    private fun custom(url: String = FeedTestServer.CUSTOM_FEEDS_URL): FeedSource.Custom {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        return requireNotNull(FeedSource.Custom.of(url, UserPin(Feed.FUEL, Base64.getEncoder().encodeToString(pair.public.encoded))))
    }

    @Test fun `no If-None-Match header is sent to a custom source, and its ETag is never handed back`() = runTest {
        respond(200, "{}", "ETag" to "\"operator-chosen\"")
        val reply = client.envelope(custom(), Feed.FUEL, etag = "\"operator-chosen\"", gate = open)
        val request = requireNotNull(server.takeRequest(1, TimeUnit.SECONDS))
        assertNull(request.headers["If-None-Match"])
        assertNull((reply as FeedReply.Body).etag)
    }

    @Test fun `gig_log's own source sends If-None-Match and reads a 304 as not modified`() = runTest {
        respond(304)
        val reply = client.envelope(FeedSource.GigLog, Feed.FUEL, etag = "\"v1\"", gate = open)
        val request = requireNotNull(server.takeRequest(1, TimeUnit.SECONDS))
        assertEquals("\"v1\"", request.headers["If-None-Match"])
        assertEquals("/feeds/fuel/envelope.json", request.url.encodedPath)
        assertEquals(FeedTestServer.GIG_LOG_HOST, request.url.host)
        assertEquals(FeedReply.NotModified, reply)
    }

    @Test fun `gig_log's ETag is handed back for the next conditional request`() = runTest {
        respond(200, "{}", "ETag" to "\"v2\"")
        val reply = client.envelope(FeedSource.GigLog, Feed.FUEL, etag = null, gate = open) as FeedReply.Body
        assertEquals("\"v2\"", reply.etag)
        assertNull(requireNotNull(server.takeRequest(1, TimeUnit.SECONDS)).headers["If-None-Match"])
    }

    @Test fun `a redirect is refused and never followed`() = runTest {
        respond(302, "", "Location" to "https://${FeedTestServer.CUSTOM_HOST}/elsewhere/envelope.json")
        val reply = client.envelope(custom(), Feed.FUEL, etag = null, gate = open)
        assertEquals(FeedReply.Failed(FeedFetchFailure.REDIRECT), reply)
        assertEquals(1, server.requestCount)
        assertEquals("a refused redirect is not retried", emptyList<Int>(), delays)
    }

    @Test fun `an http URL is refused before any request`() = runTest {
        val reply = client.envelope(custom("http://${FeedTestServer.CUSTOM_HOST}/feeds/"), Feed.FUEL, etag = null, gate = open)
        assertEquals(FeedReply.Failed(FeedFetchFailure.INSECURE_URL), reply)
        val payload = client.payload(custom("http://${FeedTestServer.CUSTOM_HOST}/feeds/"), Feed.FUEL, sha, 2, open)
        assertEquals(FeedReply.Failed(FeedFetchFailure.INSECURE_URL), payload)
        assertEquals(0, server.requestCount)
    }

    @Test fun `requests carry a fixed User-Agent, no cookie and no other application header`() = runTest {
        respond(200, "{}", "Set-Cookie" to "track=1; Path=/")
        respond(200, "{}")
        val source = custom()
        client.envelope(source, Feed.FUEL, etag = null, gate = open)
        client.envelope(source, Feed.FUEL, etag = null, gate = open)
        server.takeRequest(1, TimeUnit.SECONDS)
        val second = requireNotNull(server.takeRequest(1, TimeUnit.SECONDS))
        assertNull(second.headers["Cookie"])
        assertEquals(FeedClient.USER_AGENT, second.headers["User-Agent"])
        // OkHttp's own transport headers only (HTTP/2 pseudo-headers, or Host and Connection on HTTP/1.1): no
        // identifier, no custom header.
        val names = second.headers.names().map { it.lowercase() }.filterNot { it.startsWith(":") || it == "host" || it == "connection" }
        assertEquals(setOf("accept-encoding", "user-agent"), names.toSet())
    }

    @Test fun `a payload is fetched by its signed sha256 from the envelope's feed directory`() = runTest {
        respond(200, "{}")
        val reply = client.payload(custom(), Feed.FUEL, sha, 2, open)
        assertArrayEquals("{}".toByteArray(), (reply as FeedReply.Body).bytes)
        val request = requireNotNull(server.takeRequest(1, TimeUnit.SECONDS))
        assertEquals(FeedTestServer.CUSTOM_HOST, request.url.host)
        assertEquals("/feeds/fuel/$sha.json", request.url.encodedPath)
        assertNull("a payload request is never conditional", request.headers["If-None-Match"])
    }

    @Test fun `a payload path that is not a sha256 is refused before any request`() = runTest {
        val reply = client.payload(custom(), Feed.FUEL, "../../other", 2, open)
        assertEquals(FeedReply.Failed(FeedFetchFailure.MALFORMED_PATH), reply)
        assertEquals(0, server.requestCount)
    }

    @Test fun `an edge-cache miss is retried twice, a minute apart, then given up`() = runTest {
        repeat(3) { respond(404) }
        val reply = client.payload(custom(), Feed.FUEL, sha, 2, open)
        assertEquals(FeedReply.Failed(FeedFetchFailure.NOT_FOUND), reply)
        assertEquals(3, server.requestCount)
        assertEquals(listOf(1, 2), delays)
        assertEquals(60_000L, FeedClient.RETRY_DELAY_MS)
    }

    @Test fun `a retry that finds the file succeeds`() = runTest {
        respond(404)
        respond(200, "{}")
        val reply = client.payload(custom(), Feed.FUEL, sha, 2, open)
        assertTrue(reply is FeedReply.Body)
        assertEquals(listOf(1), delays)
    }

    @Test fun `the gate is asked before every request, retries included`() = runTest {
        assertEquals(FeedReply.Denied, client.envelope(custom(), Feed.FUEL, etag = null, gate = FeedGate { false }))
        assertEquals(0, server.requestCount)

        respond(404)
        var asks = 0
        val reply = client.payload(custom(), Feed.FUEL, sha, 2, FeedGate { asks++ == 0 })
        assertEquals(FeedReply.Denied, reply)
        assertEquals(1, server.requestCount)
        assertEquals(2, asks)
    }

    @Test fun `a body larger than its cap is refused`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body(Buffer().write(ByteArray(3))).build())
        assertEquals(FeedReply.Failed(FeedFetchFailure.TOO_LARGE), client.payload(custom(), Feed.FUEL, sha, 2, open))
        // Chunked, so no Content-Length tells the client in advance.
        server.enqueue(MockResponse.Builder().code(200).chunkedBody(Buffer().write(ByteArray(3)), 1).build())
        assertEquals(FeedReply.Failed(FeedFetchFailure.TOO_LARGE), client.payload(custom(), Feed.FUEL, sha, 2, open))
    }
}
