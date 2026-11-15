package cloud.trotter.log.gig.feed

import android.content.Context
import android.content.res.AssetManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import cloud.trotter.feed.contract.Feed
import cloud.trotter.feed.contract.FeedRefusal
import cloud.trotter.log.gig.core.data.capability.RuleCapabilityRepository
import cloud.trotter.log.gig.core.data.feed.AppFeedPolicy
import cloud.trotter.log.gig.core.data.feed.FeedError
import cloud.trotter.log.gig.core.data.feed.FeedRepository
import cloud.trotter.log.gig.core.data.feed.RulesCheckOutcome
import cloud.trotter.log.gig.core.data.feed.RulesFeedSwitch
import cloud.trotter.log.gig.core.data.settings.AppPreferencesRepository
import cloud.trotter.log.gig.core.datastore.capability.RuleCapabilityDataSource
import cloud.trotter.log.gig.core.datastore.feed.RulesFeedDataSource
import cloud.trotter.log.gig.core.network.feed.FeedClient
import cloud.trotter.log.gig.core.pipeline.rules.JsonRuleInterpreter
import cloud.trotter.log.gig.core.pipeline.rules.TestRulesFeed
import cloud.trotter.log.gig.domain.action.RuleAction
import cloud.trotter.log.gig.domain.model.accessibility.UiNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import java.io.ByteArrayInputStream
import java.io.File
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * #640 acceptance: the rules feed through the feed channel and the batch loader. Feeds are signed with the build's
 * test-only gig.log rules key (root `feedTestPins`) and verified through `FeedTrust.shipped()`, served over TLS by a
 * fake server answering for gig.log's host. Recognition is read from the interpreter itself.
 */
class RulesFeedTest {
    @get:Rule val tmp = TemporaryFolder()

    private val fake = FeedTestServer()
    private val site = RulesSite().also { fake.server.dispatcher = it }
    private val client = FeedClient(fake.client) { }
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    @Volatile private var now = TestRulesFeed.NOW + HOUR
    private val clock = object : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = Instant.ofEpochMilli(now)
    }

    private val switchStore = MemoryPreferences()
    private val switch = RulesFeedSwitch(RulesFeedDataSource(switchStore), scope)
    private val policy = AppFeedPolicy(
        mock<AppPreferencesRepository> { on { isGasPriceAuto } doReturn MutableStateFlow(false) }, switch, scope,
    )
    private val grants = RuleCapabilityRepository(RuleCapabilityDataSource(MemoryPreferences()), scope, "test")

    @After fun tearDown() {
        scope.cancel()
        fake.close()
    }

    // --- the phone ---

    private val bundled = """
        { "format_version": 2, "platform_id": "doordash.driver", "screens": [
          { "id": "doordash.screen.sensitive.known", "priority": 0, "overrideable": false,
            "parse": { "as": "sensitive" }, "require": { "exists": { "hasText": "Available Balance" } } },
          { "id": "doordash.screen.idle_map", "priority": 10, "require": { "exists": { "hasText": "MapView" } } } ] }
    """.trimIndent()

    private fun context(): Context {
        val assets = mock<AssetManager> {
            on { list("rules") } doReturn arrayOf("doordash.json")
            on { open("rules/doordash.json") } doAnswer { ByteArrayInputStream(bundled.toByteArray()) }
        }
        return mock { on { this.assets } doReturn assets }
    }

    private class Phone(val interpreter: JsonRuleInterpreter, val feed: RulesFeed, val repository: FeedRepository<RulesCandidate>)

    /** A phone start: bundled rules first, then the feed's committed generation. */
    private fun boot(root: File = tmp.root): Phone = runBlocking {
        val interpreter = JsonRuleInterpreter(context(), grants)
        interpreter.loadDefaults()
        val repository = FeedRepository(Feed.RULES, RulesFeedStager(interpreter), client, policy, root, clock, buildTimeMs = TestRulesFeed.NOW - DAY)
        val feed = RulesFeed(interpreter, repository, switch, policy, clock)
        feed.start()
        Phone(interpreter, feed, repository)
    }

    private fun Phone.recognizes(text: String): String? =
        interpreter.screenRuleset?.matchFirst(UiNode(children = listOf(UiNode(text = text))))?.ruleId

    // --- the site ---

    private fun remote(marker: String, extra: String = "") = """
        { "format_version": 2, "platform_id": "doordash.driver", "screens": [
          { "id": "doordash.screen.sensitive.known", "priority": 0, "overrideable": false,
            "parse": { "as": "sensitive" }, "require": { "exists": { "hasText": "Available Balance" } } },
          { "id": "doordash.screen.marker_$marker", "priority": 20, "require": { "exists": { "hasText": "$marker" } } }$extra ] }
    """.trimIndent()

    /** Publishes a generation signed by the pinned test key; returns its sequence. */
    private fun publish(sequence: Long, json: String, envelope: (String) -> ByteArray = { TestRulesFeed.envelope(it) }): Long {
        val file = TestRulesFeed.File("doordash", json)
        site.publish(envelope(TestRulesFeed.manifest(listOf(file), sequence)), file)
        return sequence
    }

    private suspend fun turnOn() = assertTrue(switch.set(true))

    // --- acceptance ---

    @Test fun `a signed generation is staged, committed and made live`() = runBlocking {
        val phone = boot()
        assertNull(phone.recognizes("MarkerA"))
        publish(TestRulesFeed.NOW, remote("MarkerA"))
        assertEquals(RulesCheckOutcome.UPDATED, phone.feed.checkNow())
        assertEquals("doordash.screen.marker_MarkerA", phone.recognizes("MarkerA"))
        assertEquals("doordash.screen.sensitive.known", phone.recognizes("Available Balance"))
        assertEquals(TestRulesFeed.NOW, phone.feed.status.value.liveIssuedAt)
    }

    @Test fun `an unsigned or wrongly signed generation never changes recognition`() = runBlocking {
        val phone = boot()
        publish(TestRulesFeed.NOW, remote("MarkerA")) { manifest ->
            """{"manifest":${kotlinx.serialization.json.JsonPrimitive(manifest)},"key":"${TestRulesFeed.primaryKeyId}"}""".toByteArray()
        }
        assertEquals(RulesCheckOutcome.FAILED, phone.feed.checkNow())
        assertEquals(FeedError.Refused(FeedRefusal.Malformed("sig")), phone.repository.state.value.lastError)

        val stranger = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        publish(TestRulesFeed.NOW, remote("MarkerA")) { TestRulesFeed.envelope(it, key = stranger.private) }
        assertEquals(RulesCheckOutcome.FAILED, phone.feed.checkNow())
        assertEquals(FeedError.Refused(FeedRefusal.BadSignature), phone.repository.state.value.lastError)

        assertNull(phone.recognizes("MarkerA"))
        assertEquals("doordash.screen.idle_map", phone.recognizes("MapView"))
    }

    @Test fun `an older or reused-sequence generation never changes recognition`() = runBlocking {
        val phone = boot()
        val live = publish(TestRulesFeed.NOW, remote("MarkerA"))
        assertEquals(RulesCheckOutcome.UPDATED, phone.feed.checkNow())

        publish(live - HOUR, remote("MarkerB"))
        assertEquals(RulesCheckOutcome.FAILED, phone.feed.checkNow())
        assertEquals(FeedError.Refused(FeedRefusal.Rollback), phone.repository.state.value.lastError)

        publish(live, remote("MarkerC"))
        assertEquals(RulesCheckOutcome.FAILED, phone.feed.checkNow())
        assertEquals(FeedError.Refused(FeedRefusal.ReusedSequence), phone.repository.state.value.lastError)

        assertEquals("doordash.screen.marker_MarkerA", phone.recognizes("MarkerA"))
        assertNull(phone.recognizes("MarkerB"))
        assertNull(phone.recognizes("MarkerC"))
    }

    @Test fun `a generation naming a source other than gig_log is refused`() = runBlocking {
        val phone = boot()
        val file = TestRulesFeed.File("doordash", remote("MarkerA"))
        site.publish(TestRulesFeed.envelope(TestRulesFeed.manifest(listOf(file), TestRulesFeed.NOW, source = "custom:0123456789abcdef")), file)
        assertEquals(RulesCheckOutcome.FAILED, phone.feed.checkNow())
        assertEquals(FeedError.Refused(FeedRefusal.WrongSource), phone.repository.state.value.lastError)
        assertNull(phone.recognizes("MarkerA"))
    }

    @Test fun `a generation the batch loader rejects keeps the rules in use`() = runBlocking {
        val phone = boot()
        // A remote non-overrideable rule must have the sensitive shape; this one does not, so the loader refuses it.
        publish(TestRulesFeed.NOW, remote("MarkerA", """,
          { "id": "doordash.screen.pinned", "priority": 5, "overrideable": false, "require": { "exists": { "hasText": "Pinned" } } }"""))
        assertEquals(RulesCheckOutcome.FAILED, phone.feed.checkNow())
        assertEquals(FeedError.Stage("generation_rejected"), phone.repository.state.value.lastError)
        assertNull(phone.recognizes("MarkerA"))
    }

    @Test fun `offline, start-up uses the cache, then the bundled rules`() = runBlocking {
        publish(TestRulesFeed.NOW, remote("MarkerA"))
        assertEquals(RulesCheckOutcome.UPDATED, boot().feed.checkNow())

        fake.close()
        val offline = boot()
        assertEquals("doordash.screen.marker_MarkerA", offline.recognizes("MarkerA"))

        // The cached generation no longer re-verifies: start-up falls back to the bundled rules.
        val payloads = tmp.root.walkTopDown().filter { it.isFile && it.parentFile?.name?.startsWith("gen-") == true && it.name != "meta.json" }.toList()
        assertTrue(payloads.isNotEmpty())
        payloads.forEach { it.writeText("{}") }
        val bundledOnly = boot()
        assertNull(bundledOnly.recognizes("MarkerA"))
        assertEquals("doordash.screen.idle_map", bundledOnly.recognizes("MapView"))
    }

    @Test fun `a remote rule grants no capability`() = runBlocking {
        val phone = boot()
        val offer = """,
          { "id": "doordash.screen.offer_card", "priority": 30, "enables": ["accept_offer"],
            "bind": { "acceptButton": { "find": { "hasText": "Accept" } } },
            "require": { "exists": { "hasText": "Accept" } } }"""
        publish(TestRulesFeed.NOW, remote("MarkerA", offer))
        assertEquals(RulesCheckOutcome.UPDATED, phone.feed.checkNow())
        val capability = grants.capabilities.value.single { it.ruleId == "doordash.screen.offer_card" }
        assertEquals("feed:gig.log/doordash", capability.source)
        assertFalse(grants.isActionGranted("doordash.screen.offer_card", RuleAction.ACCEPT_OFFER))
        assertFalse(capability.key in grants.grantedKeys.value)
        assertFalse(capability.key in grants.deniedKeys.value)
    }

    @Test fun `with the switch off nothing is requested, except one fetch per tap`() = runBlocking {
        val phone = boot()
        publish(TestRulesFeed.NOW, remote("MarkerA"))
        phone.feed.onPlatformForeground()
        now += 2 * HOUR
        phone.feed.onPlatformForeground()
        assertEquals(0, site.requests.size)

        assertEquals(RulesCheckOutcome.UPDATED, phone.feed.checkNow())
        assertEquals(1, site.envelopeRequests())
        assertFalse("a tap's check does not turn the switch on", switch.enabled.value)

        assertEquals(RulesCheckOutcome.NO_UPDATE, phone.feed.checkNow())
        assertEquals("one envelope per tap", 2, site.envelopeRequests())
        phone.feed.onPlatformForeground()
        assertEquals(2, site.envelopeRequests())
    }

    @Test fun `a foreground check runs at most once an hour`() = runBlocking {
        val phone = boot()
        turnOn()
        publish(TestRulesFeed.NOW, remote("MarkerA"))
        phone.feed.onPlatformForeground()
        assertEquals(1, site.envelopeRequests())
        assertEquals("doordash.screen.marker_MarkerA", phone.recognizes("MarkerA"))

        now += 30 * MINUTE
        phone.feed.onPlatformForeground()
        now += 29 * MINUTE
        phone.feed.onPlatformForeground()
        assertEquals(1, site.envelopeRequests())

        now += MINUTE
        phone.feed.onPlatformForeground()
        assertEquals(2, site.envelopeRequests())
    }

    @Test fun `Check now with the switch on skips the hourly limit`() = runBlocking {
        val phone = boot()
        turnOn()
        publish(TestRulesFeed.NOW, remote("MarkerA"))
        phone.feed.onPlatformForeground()
        assertEquals(RulesCheckOutcome.NO_UPDATE, phone.feed.checkNow())
        assertEquals(2, site.envelopeRequests())
    }

    @Test fun `the notice's Keep rules up to date turns the switch on and checks at once`() = runBlocking {
        val phone = boot()
        publish(TestRulesFeed.NOW, remote("MarkerA"))
        assertEquals(RulesCheckOutcome.UPDATED, phone.feed.enableAndCheck())
        assertTrue(switch.enabled.value)
        assertEquals("doordash.screen.marker_MarkerA", phone.recognizes("MarkerA"))
    }

    /** The rules feed directory of gig.log's site: one envelope, payloads by sha256; counts every request. */
    private class RulesSite : Dispatcher() {
        @Volatile private var envelope: ByteArray? = null
        private val payloads = ConcurrentHashMap<String, ByteArray>()
        val requests = CopyOnWriteArrayList<String>()

        fun publish(envelope: ByteArray, vararg files: TestRulesFeed.File) {
            this.envelope = envelope
            files.forEach { payloads["/feeds/rules/${it.sha256}.json"] = it.bytes }
        }

        fun envelopeRequests(): Int = requests.count { it.endsWith("/envelope.json") }

        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.url.encodedPath
            requests += path
            val body = if (path == "/feeds/rules/envelope.json") envelope else payloads[path]
            body ?: return MockResponse.Builder().code(404).build()
            return MockResponse.Builder().code(200).body(Buffer().write(body)).build()
        }
    }

    private class MemoryPreferences : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(data.value).also { data.value = it }
    }

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        const val DAY = 24 * HOUR
    }
}
