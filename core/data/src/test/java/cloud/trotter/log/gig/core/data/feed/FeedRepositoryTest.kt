package cloud.trotter.log.gig.core.data.feed

import cloud.trotter.feed.contract.Feed
import cloud.trotter.feed.contract.FeedRefusal
import cloud.trotter.log.gig.core.network.feed.FeedClient
import cloud.trotter.log.gig.core.network.feed.FeedFetchFailure
import cloud.trotter.log.gig.core.network.feed.FeedSource
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** #1565 acceptance: the feed channel's stage, commit, activate and recovery, its consent, and its transport rules. */
class FeedRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()

    private val fake = FeedTestServer()
    private val site = FeedSite().also { fake.server.dispatcher = it }
    private val client = FeedClient(fake.client) { }
    private val clock = Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC)
    private val fuel = SignedSource(Feed.FUEL)
    private val policy = FakeFeedPolicy(enabled = true, source = fuel.source)

    private val gen1 = fuel.generation(NOW - 3 * HOUR, "one")
    private val gen2 = fuel.generation(NOW - 2 * HOUR, "two")

    @After fun tearDown() {
        site.release()
        fake.close()
    }

    private fun root(): File = File(tmp.root, "feeds")

    private fun repo(
        feed: Feed = Feed.FUEL,
        policy: FeedPolicy = this.policy,
        crash: CrashPoints = CrashPoints { },
    ) = FeedRepository(feed, TestStager(), client, policy, root(), clock, buildTimeMs = NOW - 30 * 24 * HOUR, bundled = null, crashPoints = crash)

    private fun feedDir(source: FeedSource = fuel.source, feed: Feed = Feed.FUEL) = File(root(), "${source.id}/${feed.wire}")

    private fun accept(repository: FeedRepository<String>, generation: SignedSource.Generation) = runBlocking {
        site.publish(Feed.FUEL, generation)
        assertEquals(FeedRefresh.Accepted(generation.sequence), repository.refresh())
    }

    // --- stage, commit, activate ---

    @Test fun `an accepted generation is staged, committed and activated, with anchors only in the state`() = runBlocking {
        val repository = repo()
        repository.start()
        accept(repository, gen1)
        val state = repository.state.value
        assertEquals("one", state.value)
        assertEquals(gen1.sequence, state.issuedAt)
        assertEquals(gen1.sequence - 7 * 24 * HOUR, state.period)
        assertNull(state.lastError)
        val dir = feedDir()
        assertEquals("gen-${gen1.sequence}", File(dir, "CURRENT").readText())
        val meta = Json.parseToJsonElement(File(dir, "gen-${gen1.sequence}/meta.json").readText()).jsonObject
        assertEquals(gen1.sequence, meta["high_water"]!!.jsonObject["sequence"]!!.jsonPrimitive.long)
        assertEquals("one", meta["carry"]!!.jsonObject["seen"]!!.jsonArray.single().jsonPrimitive.content)
    }

    @Test fun `a crash between commit and activate re-activates from CURRENT at start`() = runBlocking {
        val dying = repo(crash = CrashPoints { if (it == CrashPoint.COMMITTED) throw SimulatedDeath() })
        dying.start()
        site.publish(Feed.FUEL, gen1)
        assertTrue(runCatching { dying.refresh() }.exceptionOrNull() is SimulatedDeath)
        assertNull("never activated", dying.state.value.value)

        val restarted = repo()
        restarted.start()
        assertEquals("one", restarted.state.value.value)
        assertEquals(gen1.sequence, restarted.state.value.issuedAt)
        val before = site.requests.size
        assertEquals("the committed high-water came back with it", FeedRefresh.Unchanged, restarted.refresh())
        assertEquals("only the envelope was fetched", before + 1, site.requests.size)
    }

    @Test fun `a crash before commit leaves the old generation live`() = runBlocking {
        val first = repo()
        first.start()
        accept(first, gen1)

        val dying = repo(crash = CrashPoints { if (it == CrashPoint.STAGED) throw SimulatedDeath() })
        dying.start()
        site.publish(Feed.FUEL, gen2)
        assertTrue(runCatching { dying.refresh() }.exceptionOrNull() is SimulatedDeath)
        assertTrue("gen2 was staged", File(feedDir(), "gen-${gen2.sequence}").isDirectory)

        val restarted = repo()
        restarted.start()
        assertEquals("one", restarted.state.value.value)
        assertFalse("the uncommitted stage is cleared", File(feedDir(), "gen-${gen2.sequence}").exists())
        assertEquals("and gen2 is still a candidate", FeedRefresh.Accepted(gen2.sequence), restarted.refresh())
    }

    @Test fun `a corrupted CURRENT falls back to the previous generation with the high-water unchanged, then to the baseline`() =
        runBlocking {
            val first = repo()
            first.start()
            accept(first, gen1)
            accept(first, gen2)

            File(feedDir(), "gen-${gen2.sequence}/${gen2.sha256}").writeText("""{"v":"evil","period":1}""")
            val second = repo()
            second.start()
            assertEquals("one", second.state.value.value)
            assertEquals("served with its own issued_at", gen1.sequence, second.state.value.issuedAt)
            site.publish(Feed.FUEL, gen2)
            assertEquals("gen2 is still the high-water", FeedRefresh.Unchanged, second.refresh())
            site.publish(Feed.FUEL, gen1)
            assertEquals(FeedRefresh.Failed(FeedError.Refused(FeedRefusal.Rollback)), second.refresh())

            File(feedDir(), "gen-${gen1.sequence}/meta.json").writeText("{")
            val third = repo()
            third.start()
            assertNull("the baseline: no feed value", third.state.value.value)
            site.publish(Feed.FUEL, fuel.generation(NOW - 150 * 60 * 1000, "between"))
            assertEquals(FeedRefresh.Failed(FeedError.Refused(FeedRefusal.Rollback)), third.refresh())
        }

    @Test fun `a garbled CURRENT pointer falls back to the previous generation and keeps the high-water`() = runBlocking {
        val first = repo()
        first.start()
        accept(first, gen1)
        accept(first, gen2)
        File(feedDir(), "CURRENT").writeText("\u0000garbage")
        val second = repo()
        second.start()
        assertEquals("one", second.state.value.value)
        site.publish(Feed.FUEL, gen2)
        assertEquals(FeedRefresh.Unchanged, second.refresh())
    }

    @Test fun `only the current and previous generations are kept`() = runBlocking {
        val repository = repo()
        repository.start()
        accept(repository, gen1)
        accept(repository, gen2)
        val gen3 = fuel.generation(NOW - HOUR, "three")
        accept(repository, gen3)
        val held = feedDir().listFiles()!!.filter { it.isDirectory }.map { it.name }.toSet()
        assertEquals(setOf("gen-${gen2.sequence}", "gen-${gen3.sequence}"), held)
        assertEquals("gen-${gen2.sequence}", File(feedDir(), "PREVIOUS").readText())
    }

    @Test fun `a file already held with the same verified sha256 is not downloaded again`() = runBlocking {
        val repository = repo()
        repository.start()
        accept(repository, gen1)
        val resigned = fuel.generation(NOW - 2 * HOUR, "one", period = gen1.sequence - 7 * 24 * HOUR)
        assertEquals(gen1.sha256, resigned.sha256)
        val before = site.requests.size
        accept(repository, resigned)
        assertEquals("the envelope only", before + 1, site.requests.size)
        assertEquals("one", repository.state.value.value)
    }

    @Test fun `a generation the stager refuses commits nothing and keeps last-good`() = runBlocking {
        val repository = repo()
        repository.start()
        accept(repository, gen1)
        site.publish(Feed.FUEL, fuel.generation(NOW - HOUR, "bad"))
        assertEquals(FeedRefresh.Failed(FeedError.Stage("out_of_bounds")), repository.refresh())
        assertEquals("one", repository.state.value.value)
        assertEquals(FeedError.Stage("out_of_bounds"), repository.state.value.lastError)
        assertEquals("gen-${gen1.sequence}", File(feedDir(), "CURRENT").readText())
        assertEquals(1, feedDir().listFiles()!!.count { it.isDirectory })
    }

    @Test fun `a phone clock behind the feed reaches lastError and keeps last-good`() = runBlocking {
        val repository = repo()
        repository.start()
        accept(repository, gen1)
        site.publish(Feed.FUEL, fuel.generation(NOW + 25 * HOUR, "future"))
        assertEquals(FeedRefresh.Failed(FeedError.Refused(FeedRefusal.ClockBehind)), repository.refresh())
        assertEquals("one", repository.state.value.value)
        assertEquals(FeedError.Refused(FeedRefusal.ClockBehind), repository.state.value.lastError)
    }

    // --- consent ---

    @Test fun `consent off sends no request at all`() = runBlocking {
        site.publish(Feed.FUEL, gen1)
        policy.enabled.value = false
        val repository = repo()
        repository.start()
        assertEquals(FeedRefresh.Denied, repository.refresh())
        assertEquals(0, fake.server.requestCount)
        assertFalse(File(feedDir(), "CURRENT").exists())
    }

    @Test fun `gig_log's feeds have no pins yet, so nothing is requested from it`() = runBlocking {
        policy.source.value = FeedSource.GigLog
        val repository = repo()
        repository.start()
        assertEquals(FeedRefresh.Failed(FeedError.Refused(FeedRefusal.NoTrustedEpoch)), repository.refresh())
        assertEquals(0, fake.server.requestCount)
    }

    @Test fun `turning the feature off mid-fetch cancels it, and nothing is committed`() = runBlocking {
        val repository = repo()
        repository.start()
        site.publish(Feed.FUEL, gen1)
        site.hold("/feeds/fuel/${gen1.sha256}.json")
        val refresh = async { repository.refresh() }
        assertTrue("the payload request is in flight", withContext(Dispatchers.IO) { site.awaitHeld() })
        policy.enabled.value = false
        assertEquals(FeedRefresh.Cancelled, withTimeout(5_000) { refresh.await() })
        assertFalse(File(feedDir(), "CURRENT").exists())
        assertNull(repository.state.value.value)
    }

    @Test fun `changing the source cancels the old source's work`() = runBlocking {
        val repository = repo()
        repository.start()
        site.publish(Feed.FUEL, gen1)
        site.hold("/feeds/fuel/${gen1.sha256}.json")
        val refresh = async { repository.refresh() }
        assertTrue(withContext(Dispatchers.IO) { site.awaitHeld() })
        policy.source.value = SignedSource(Feed.FUEL).source
        assertEquals(FeedRefresh.Cancelled, withTimeout(5_000) { refresh.await() })
        assertFalse(File(feedDir(), "CURRENT").exists())
    }

    // --- one-shot grant ---

    @Test fun `a one-shot grant runs one fetch while the feature is off, and only one`() = runBlocking {
        policy.enabled.value = false
        val repository = repo()
        repository.start()
        site.publish(Feed.FUEL, gen1)
        val grant = OneShotGrant.forUserAct(Feed.FUEL, fuel.source)
        assertEquals(FeedRefresh.Accepted(gen1.sequence), repository.refresh(grant))
        assertEquals("one", repository.state.value.value)
        val before = fake.server.requestCount
        assertEquals("spent", FeedRefresh.Denied, repository.refresh(grant))
        assertEquals(before, fake.server.requestCount)
    }

    @Test fun `a policy whose switches are fixed flows still runs a granted fetch`() = runBlocking {
        val fixed = object : FeedPolicy {
            override fun enabled(feed: Feed) = flowOf(false)
            override fun source(feed: Feed) = flowOf<FeedSource>(fuel.source)
        }
        val repository = repo(policy = fixed)
        repository.start()
        site.publish(Feed.FUEL, gen1)
        assertEquals(FeedRefresh.Accepted(gen1.sequence), repository.refresh(OneShotGrant.forUserAct(Feed.FUEL, fuel.source)))
    }

    @Test fun `a one-shot grant cannot be used for another feature, another source, or after cancellation`() = runBlocking {
        policy.enabled.value = false
        site.publish(Feed.FUEL, gen1)

        val constants = SignedSource(Feed.CONSTANTS)
        val constantsRepo = repo(Feed.CONSTANTS, FakeFeedPolicy(enabled = false, source = constants.source))
        constantsRepo.start()
        assertEquals("another feature", FeedRefresh.Denied, constantsRepo.refresh(OneShotGrant.forUserAct(Feed.FUEL, constants.source)))

        val repository = repo()
        repository.start()
        val otherSource = SignedSource(Feed.FUEL).source
        assertEquals("another source", FeedRefresh.Denied, repository.refresh(OneShotGrant.forUserAct(Feed.FUEL, otherSource)))

        var minted: OneShotGrant? = null
        val act = launch(start = CoroutineStart.UNDISPATCHED) {
            minted = OneShotGrant.forUserAct(Feed.FUEL, fuel.source)
            awaitCancellation()
        }
        act.cancelAndJoin()
        assertEquals("the user act was cancelled", FeedRefresh.Denied, repository.refresh(minted!!))
        assertEquals(0, fake.server.requestCount)

        site.hold("/feeds/fuel/${gen1.sha256}.json")
        val grant = OneShotGrant.forUserAct(Feed.FUEL, fuel.source)
        val inFlight = async { repository.refresh(grant) }
        assertTrue(withContext(Dispatchers.IO) { site.awaitHeld() })
        inFlight.cancelAndJoin()
        site.release()
        val count = fake.server.requestCount
        assertEquals("a grant dies with its cancelled call", FeedRefresh.Denied, repository.refresh(grant))
        assertEquals(count, fake.server.requestCount)
        assertFalse(File(feedDir(), "CURRENT").exists())
    }

    // --- transport rules through the repository ---

    @Test fun `no If-None-Match header is sent to a custom source`() = runBlocking {
        site.envelopeHeaders = listOf("ETag" to "\"operator-chosen\"")
        val repository = repo()
        repository.start()
        accept(repository, gen1)
        assertEquals(FeedRefresh.Unchanged, repository.refresh())
        assertTrue(site.requests.isNotEmpty())
        site.requests.forEach { assertNull(it.headers["If-None-Match"]) }
        val meta = File(feedDir(), "gen-${gen1.sequence}/meta.json").readText()
        assertFalse("a custom source's ETag is never stored", meta.contains("operator-chosen"))
    }

    @Test fun `a redirect is refused and an http URL is refused`() = runBlocking {
        site.publish(Feed.FUEL, gen1)
        site.redirect = true
        val repository = repo()
        repository.start()
        assertEquals(FeedRefresh.Failed(FeedError.Fetch(FeedFetchFailure.REDIRECT)), repository.refresh())
        assertEquals(1, fake.server.requestCount)

        site.redirect = false
        policy.source.value = fuel.at("http://${FeedTestServer.CUSTOM_HOST}/feeds/")
        assertEquals(FeedRefresh.Failed(FeedError.Fetch(FeedFetchFailure.INSECURE_URL)), repository.refresh())
        assertEquals(1, fake.server.requestCount)
        assertEquals(FeedError.Fetch(FeedFetchFailure.INSECURE_URL), repository.state.value.lastError)
    }
}
