package cloud.trotter.log.gig.worker

import androidx.work.ListenableWorker
import cloud.trotter.census.contract.ClickedIdentity
import cloud.trotter.census.contract.SkeletonSchema
import cloud.trotter.census.contract.TapRef
import cloud.trotter.census.contract.UiSkeletonDto
import cloud.trotter.census.contract.UiSkeletonNodeDto
import cloud.trotter.log.gig.core.network.census.CensusApi
import cloud.trotter.log.gig.core.network.census.CensusBudget
import cloud.trotter.log.gig.core.network.census.EnrolResult
import cloud.trotter.log.gig.core.network.census.UploadResult
import cloud.trotter.log.gig.core.pipeline.census.SkeletonBuilder
import cloud.trotter.log.gig.domain.capture.ReplayMetadata
import cloud.trotter.log.gig.domain.model.accessibility.UiNode
import cloud.trotter.log.gig.domain.state.Platform
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * #1634 D7 and §6: a day's click evidence goes out once the day has ended, as one frozen batch under a `c-` batch id,
 * resent unchanged on a retryable answer, deleted on a final one, split in key order on a 413. The uploaded bytes carry
 * none of the tapped node's or its owner's words.
 */
@RunWith(RobolectricTestRunner::class)
class CensusClickedUploadTest {

    private val yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1)

    private fun accepted(n: Int) = UploadResult.Accepted(n, 0, emptyMap(), CensusBudget(200, 1000, 30, 300))

    /** A synthetic clicked item of layout [number] on yesterday's day (contract-built, so its bytes are canonical). */
    private fun item(number: Int): Pair<UiSkeletonDto, String> {
        val item = UiSkeletonDto(
            schemaId = SkeletonSchema.SCHEMA_ID_CLICKED, hashDomain = 1, filterRev = 1,
            fingerprint = number.toString(16).padStart(64, '0'), platform = Platform.DoorDash.wire, engineVersion = 1,
            day = yesterday.toString(), tap = TapRef(ownerPath = "root/0", generation = "0123456789abcdef"),
            root = UiSkeletonNodeDto(className = "android.widget.FrameLayout", children = listOf(
                UiSkeletonNodeDto(className = "android.widget.Button", isClickable = true),
            )),
        )
        return item to SkeletonSchema.measure(item).json
    }

    private suspend fun CensusWorkerHarness.keep(vararg items: Pair<UiSkeletonDto, String>) {
        items.forEach { (item, json) -> clicks.admit(item, json, item.platform, clickSpool.generation) }
    }

    @Test fun `a tapped node and its owner's words never reach the uploaded bytes`() = runTest {
        val h = harness()
        h.initialize()
        h.api.beforeUpload = { _, _ -> }
        val tapped = UiNode(className = "android.widget.TextView", text = "Decline offer", contentDescription = "Decline this offer now",
            hintText = "Hold to decline quickly")
        val owner = UiNode(className = "android.widget.Button", viewIdResourceName = "com.doordash.driverapp:id/decline",
            isClickable = true, contentDescription = "Decline button label", children = listOf(tapped))
        val tree = UiNode(className = "android.widget.FrameLayout", children = listOf(owner)).restoreParents()
        val built = SkeletonBuilder.clickedOutcome(tree, null, ReplayMetadata(engineVersion = 1), Platform.DoorDash, yesterday,
            null, owner, tapped, "0123456789abcdef") as SkeletonBuilder.Outcome.Built
        h.keep(built.skeleton to built.json)
        h.api.uploads += accepted(1)

        assertEquals(ListenableWorker.Result.success(), h.worker().doWork())
        val sent = h.api.bodies.single().single()
        assertEquals(built.json, sent)
        for (plain in listOf("Decline offer", "Decline this offer now", "Hold to decline quickly", "Decline button label", "Decline")) {
            assertFalse("'$plain' left the phone", sent.contains(plain))
        }
        assertEquals(CensusApi.clickedBatchId(listOf(ClickedIdentity.of(built.skeleton)!!)), h.api.batches.single())
        assertTrue(h.api.batches.single().startsWith("c-"))
        assertTrue("answered: the frozen batch is gone", h.clicks.frozen().isEmpty())
        assertEquals(1L, h.stats.uploaded.get())
    }

    @Test fun `a retryable failure resends the same bytes under the same id and never replenishes`() = runTest {
        val h = harness()
        h.initialize()
        h.api.beforeUpload = { _, _ -> }
        h.keep(item(1), item(2))
        h.api.uploads += UploadResult.TransportFailure("Timeout")
        assertEquals(ListenableWorker.Result.retry(), h.worker().doWork())
        h.keep(item(3)) // Late for a closed day: dropped.
        h.api.uploads += UploadResult.ServerUnavailable(503)
        assertEquals(ListenableWorker.Result.retry(), h.worker().doWork())
        h.api.uploads += accepted(2)
        assertEquals(ListenableWorker.Result.success(), h.worker().doWork())
        assertEquals(3, h.api.bodies.size)
        assertEquals(1, h.api.bodies.distinct().size)
        assertEquals(1, h.api.batches.distinct().size)
        assertEquals(2, h.api.bodies.first().size)
        assertTrue(h.clicks.frozen().isEmpty())
    }

    @Test fun `a final answer deletes the batch, whatever it says`() = runTest {
        for (answer in listOf(
            UploadResult.BatchQuality(mapOf("stale_day" to 1)),
            UploadResult.BadRequest,
            UploadResult.ServerUnavailable(403),
            UploadResult.Duplicate,
        )) {
            val h = harness()
            h.initialize()
            h.api.beforeUpload = { _, _ -> }
            h.keep(item(1))
            h.api.uploads += answer
            h.worker().doWork()
            assertTrue("$answer is final", h.clicks.frozen().isEmpty())
            h.clickSpool.clear()
        }
    }

    @Test fun `a 413 splits in key order, and a sent half is not sent again`() = runTest {
        val h = harness()
        h.initialize()
        h.api.beforeUpload = { _, _ -> }
        h.keep(item(1), item(2), item(3), item(4))
        h.api.uploads.addAll(listOf(UploadResult.PayloadTooLarge, accepted(2), UploadResult.TransportFailure("Timeout")))
        assertEquals(ListenableWorker.Result.retry(), h.worker().doWork())
        val (whole, first, second) = h.api.bodies
        assertEquals(whole, first + second)
        assertEquals(listOf(2, 2), listOf(first.size, second.size))
        assertEquals(3, h.api.batches.distinct().size)
        assertTrue(h.api.batches.all { it.startsWith("c-") })
        h.api.uploads += accepted(2)
        assertEquals(ListenableWorker.Result.success(), h.worker().doWork())
        assertEquals("only the unsent half goes again", second, h.api.bodies.last())
        assertEquals(h.api.batches[2], h.api.batches[3])
        assertTrue(h.clicks.frozen().isEmpty())
    }

    /** Click evidence from 9 days ago, written as the store writes it: a frozen batch and a closed-day marker. */
    private fun staleEvidence(): List<java.io.File> {
        val root = java.io.File(org.robolectric.RuntimeEnvironment.getApplication().filesDir, "census")
        val old = LocalDate.now(ZoneOffset.UTC).minusDays(9).toString()
        val batchId = "c-" + "0".repeat(32)
        val frozen = java.io.File(root, "clicks-frozen/$old.$batchId.json").apply { parentFile!!.mkdirs() }
        frozen.writeText("""{"day":"$old","batchId":"$batchId","keys":["k"],"identities":["i"],"platforms":["doordash"],"items":["{}"]}""")
        val candidates = java.io.File(root, "clicks.json").apply { writeText("""{"closed":["$old"]}""") }
        return listOf(frozen, candidates)
    }

    /** Runs one worker run set up by [setup] over stale evidence; the evidence must be gone and nothing sent. */
    private suspend fun kotlinx.coroutines.test.TestScope.expiresIn(name: String, setup: suspend (CensusWorkerHarness) -> Unit) {
        val h = harness()
        setup(h)
        val files = staleEvidence()
        h.worker().doWork()
        assertTrue("$name: expired evidence is gone", files.none { it.exists() })
        assertTrue("$name: nothing was sent", h.api.bodies.isEmpty())
    }

    @Test fun `expiry runs at the start of every run, before any check can end it`() = runTest {
        expiresIn("census off") { h -> h.initialize(enabled = false) }
        expiresIn("not enrolled") { h ->
            h.initialize()
            whenever(h.credentials.current()).thenReturn(null)
            whenever(h.credentials.pending()).thenReturn(h.credential.copy(enrolled = false))
            h.api.enrollments += EnrolResult.ServerUnavailable(503)
        }
        expiresIn("deferred") { h ->
            h.initialize()
            h.preferences.setNextAllowedAtMillis(Long.MAX_VALUE)
        }
    }
}
