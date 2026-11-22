package cloud.trotter.log.gig.worker

import android.util.Log
import androidx.work.ListenableWorker
import cloud.trotter.census.contract.SkeletonSchema
import cloud.trotter.census.contract.TapRef
import cloud.trotter.census.contract.UiSkeletonDto
import cloud.trotter.census.contract.UiSkeletonNodeDto
import cloud.trotter.log.gig.BuildConfig
import cloud.trotter.log.gig.core.data.census.CensusSpool
import cloud.trotter.log.gig.core.network.census.CensusBudget
import cloud.trotter.log.gig.core.network.census.HealthResult
import cloud.trotter.log.gig.core.network.census.PolicyResult
import cloud.trotter.log.gig.core.network.census.UploadResult
import cloud.trotter.log.gig.domain.census.CensusRunOutcome
import cloud.trotter.log.gig.domain.census.HealthKey
import cloud.trotter.log.gig.domain.state.Platform
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import timber.log.Timber
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * #1709: the census client never uploads what the server's policy refuses. An item of a delivery app the policy's
 * `acceptedPlatforms` leaves out stays queued (skeleton, envelope) or frozen (click evidence), unsent and undeleted, and
 * goes out once a policy lists the app; a policy with no readable list holds everything.
 */
@RunWith(RobolectricTestRunner::class)
class CensusPlatformHoldTest {
    private val logs = mutableListOf<Pair<Int, String>>()
    private val tree = object : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            if (tag == "Census") logs += priority to message
        }
    }

    @Before fun plant() = Timber.plant(tree)

    @After fun uproot() = Timber.uproot(tree)

    private val yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1)

    private fun accepted(n: Int) = UploadResult.Accepted(n, 0, emptyMap(), CensusBudget(200, 1000, 30, 300))

    private fun policy(json: String) = PolicyResult.Available(Json.parseToJsonElement(json).jsonObject)

    private val withoutInstacart = policy("""{"acceptedSchemaIds":["uinode.skeleton.v1"],"acceptedPlatforms":["doordash","uber","_unknown"]}""")

    /** A yesterday's clicked item of layout [number] from [platform], kept as click evidence (contract-built bytes). */
    private suspend fun CensusWorkerHarness.keepClicked(number: Int, platform: Platform): String {
        val item = UiSkeletonDto(
            schemaId = SkeletonSchema.SCHEMA_ID_CLICKED, hashDomain = 1, filterRev = 1,
            fingerprint = number.toString(16).padStart(64, '0'), platform = platform.wire, engineVersion = 1,
            day = yesterday.toString(), tap = TapRef(ownerPath = "root/0", generation = "0123456789abcdef"),
            root = UiSkeletonNodeDto(className = "android.widget.FrameLayout", children = listOf(
                UiSkeletonNodeDto(className = "android.widget.Button", isClickable = true),
            )),
        )
        val json = SkeletonSchema.measure(item).json
        clicks.admit(item, json, platform.wire, clickSpool.generation)
        return json
    }

    /** One envelope of DoorDash and one of [platform], in that order. */
    private suspend fun CensusWorkerHarness.envelopesWith(platform: Platform): Pair<CensusSpool.Spooled, CensusSpool.Spooled> {
        val doordash = queueEnvelopes(1).single()
        val other = CensusSpool.Spooled("2-99.json", doordash.fingerprint,
            doordash.itemJson.replace("\"doordash\"", "\"${platform.wire}\""))
        envelopes += other
        return doordash to other
    }

    private suspend fun CensusWorkerHarness.healthOf(platform: Platform): HealthKey {
        val key = HealthKey(yesterday.toString(), platform.wire, "7.1")
        healthSink.apply { it.record(key, null).trip(key) }
        return key
    }

    @Test fun `an app the policy does not accept stays on the phone and goes out once a policy lists it`() = runTest {
        assumeTrue("Instacart is readable only in the build that reads unbound platforms", BuildConfig.READS_UNBOUND_PLATFORMS)
        val h = harness()
        h.initialize()
        h.api.beforeUpload = { _, _ -> } // click evidence does not use the skeleton spool's in-flight marker
        h.api.policyResult = withoutInstacart
        val doordashClick = h.keepClicked(1, Platform.DoorDash)
        val instacartClick = h.keepClicked(2, Platform.Instacart)
        val doordash = h.queueBatch(1).single()
        val instacart = h.queueBatch(1, platform = Platform.Instacart.wire).single()
        val (doordashEnvelope, instacartEnvelope) = h.envelopesWith(Platform.Instacart)
        h.healthOf(Platform.DoorDash)
        h.healthOf(Platform.Instacart)
        h.api.healthResults += HealthResult.Accepted(1, emptyMap())
        h.api.uploads.addAll(listOf(accepted(1), accepted(1)))
        h.api.envelopeResults += accepted(1)

        assertEquals(ListenableWorker.Result.success(), h.worker().doWork())
        assertEquals("DoorDash's click and skeleton go out; Instacart's do not",
            listOf(listOf(doordashClick), listOf(doordash.itemJson)), h.api.bodies)
        assertEquals(listOf(listOf(doordashEnvelope.itemJson)), h.api.envelopeBodies)
        assertEquals(1, h.api.healthBodies.size)
        assertTrue(h.api.healthBodies.single().single().contains("\"doordash\""))
        assertEquals("held, not deleted", listOf(listOf(instacart)), h.queued)
        assertEquals(listOf(instacartEnvelope), h.envelopes)
        assertEquals(listOf(listOf(instacartClick)), h.clicks.frozen().map { it.items })
        assertTrue("held is not rejected", h.stats.rejectedCounts().isEmpty())
        assertEquals(0L, h.stats.platformNotChosen.get())
        assertEquals(CensusRunOutcome.UPLOADED, h.preferences.censusLastRun.first()?.outcome)
        assertEquals(listOf(Log.INFO to "census held instacart=3"), logs.filter { it.second.startsWith("census held") })

        h.api.policyResult = PolicyResult.Available(DEFAULT_POLICY)
        h.api.healthResults += HealthResult.Accepted(1, emptyMap())
        h.api.uploads.addAll(listOf(accepted(1), accepted(1)))
        h.api.envelopeResults += accepted(1)
        assertEquals(ListenableWorker.Result.success(), h.worker().doWork())
        assertEquals(listOf(listOf(instacartClick), listOf(instacart.itemJson)), h.api.bodies.drop(2))
        assertEquals(listOf(instacartEnvelope.itemJson), h.api.envelopeBodies.last())
        assertTrue(h.api.healthBodies.last().single().contains("\"instacart\""))
        assertTrue(h.queued.isEmpty())
        assertTrue(h.envelopes.isEmpty())
        assertTrue(h.clicks.frozen().isEmpty())
    }

    @Test fun `a policy with no readable platform list uploads nothing and keeps everything`() = runTest {
        for (answer in listOf(
            policy("""{"acceptedSchemaIds":["uinode.skeleton.v1"]}"""),
            policy("""{"acceptedSchemaIds":["uinode.skeleton.v1"],"acceptedPlatforms":"doordash"}"""),
            policy("""{"acceptedSchemaIds":["uinode.skeleton.v1"],"acceptedPlatforms":["doordash",1]}"""),
            PolicyResult.Unavailable(503),
        )) {
            logs.clear()
            val h = harness()
            h.initialize()
            h.api.beforeUpload = { _, _ -> }
            h.api.policyResult = answer
            val click = h.keepClicked(1, Platform.DoorDash)
            val skeleton = h.queueBatch(1).single()
            val envelope = h.queueEnvelopes(1).single()
            h.healthOf(Platform.DoorDash)

            assertEquals(ListenableWorker.Result.success(), h.worker().doWork())
            assertTrue("$answer: nothing is sent", h.api.bodies.isEmpty() && h.api.envelopeBodies.isEmpty() && h.api.healthBodies.isEmpty())
            assertEquals(listOf(listOf(skeleton)), h.queued)
            assertEquals(listOf(envelope), h.envelopes)
            assertNull(h.pending)
            assertEquals(listOf(listOf(click)), h.clicks.frozen().map { it.items })
            val lastRun = h.preferences.censusLastRun.first()
            assertEquals(CensusRunOutcome.HELD, lastRun?.outcome)
            assertEquals(3, lastRun?.detail)
            assertEquals(listOf(Log.WARN to "census policy has no platform list: holding every item this run"),
                logs.filter { it.first == Log.WARN })
            assertEquals(listOf(Log.INFO to "census held doordash=3"), logs.filter { it.second.startsWith("census held") })
            h.clickSpool.clear()
        }
    }

    @Test fun `the accepted list is read only from a well-formed field`() {
        fun read(json: String) = CensusPlatformHold.acceptedIn(Json.parseToJsonElement(json).jsonObject)
        assertEquals(setOf("doordash", "_unknown"), read("""{"acceptedPlatforms":["doordash","_unknown"]}"""))
        assertEquals(emptySet<String>(), read("""{"acceptedPlatforms":[]}"""))
        assertNull(read("""{}"""))
        assertNull(read("""{"acceptedPlatforms":null}"""))
        assertNull(read("""{"acceptedPlatforms":{"doordash":true}}"""))
        assertNull(read("""{"acceptedPlatforms":["doordash",null]}"""))
    }
}
