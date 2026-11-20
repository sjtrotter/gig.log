package cloud.trotter.log.gig.worker

import androidx.work.ListenableWorker
import cloud.trotter.census.contract.SkeletonSchema
import cloud.trotter.log.gig.core.data.census.CensusSpool
import cloud.trotter.log.gig.core.network.census.CensusApi
import cloud.trotter.log.gig.core.network.census.CensusBudget
import cloud.trotter.log.gig.core.network.census.UploadResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * #1573 A7: a matched skeleton goes in its own batch, under a batch id keyed on its rule as well as its layout, so the
 * server never answers it `duplicate` for the UNKNOWN batch of the same layout; UNKNOWN batch ids are unchanged.
 */
@RunWith(RobolectricTestRunner::class)
class CensusMatchedUploadTest {

    private val rule = "doordash.screen.delivery_summary_expanded"
    private val layout = "a".repeat(64)
    private val today = LocalDate.now(ZoneOffset.UTC)

    private fun item(id: String, fingerprint: String, ruleId: String?): CensusSpool.Spooled {
        val schema = if (ruleId != null) SkeletonSchema.SCHEMA_ID_MATCHED else SkeletonSchema.SCHEMA_ID_V2
        val rulePart = ruleId?.let { ",\"ruleId\":\"$it\"" }.orEmpty()
        return CensusSpool.Spooled(id, fingerprint, "{\"schemaId\":\"$schema\",\"platform\":\"doordash\",\"day\":\"$today\"$rulePart}")
    }

    private fun accepted(n: Int) = UploadResult.Accepted(n, 0, emptyMap(), CensusBudget(200, 1000, 30, 300))

    @Test fun `an UNKNOWN and a matched skeleton of one layout go in two batches with different ids`() = runTest {
        val h = harness()
        h.initialize()
        val unknown = item("1-1.json", layout, null)
        val matched = item("1-2.json", layout, rule)
        h.queued += listOf(unknown, matched)
        h.api.uploads += accepted(1)
        h.api.uploads += accepted(1)
        assertEquals(ListenableWorker.Result.success(), h.worker().doWork())
        assertEquals(listOf(listOf(unknown.itemJson), listOf(matched.itemJson)), h.api.bodies)
        assertEquals("the UNKNOWN batch id is unchanged", "ffe054fe7ae0cb6dc65c3af9b61d5209", h.api.batches[0])
        assertEquals(CensusApi.batchId(listOf(layout)), h.api.batches[0])
        assertEquals("m-9527fe53196e0340745404396cba6b93", h.api.batches[1])
        assertEquals(CensusApi.matchedBatchId(listOf(layout to rule)), h.api.batches[1])
        assertNotEquals(h.api.batches[0], h.api.batches[1])
        assertEquals(2L, h.stats.uploaded.get())
        assertTrue(h.queued.isEmpty())
    }

    @Test fun `a split matched batch keys each half on its rules too`() = runTest {
        val h = harness()
        h.initialize()
        val first = item("1-1.json", layout, rule)
        val second = item("1-2.json", "b".repeat(64), "doordash.screen.dash_summary")
        h.queued += listOf(first, second)
        h.api.uploads.addAll(listOf(UploadResult.PayloadTooLarge, accepted(1), accepted(1)))
        assertEquals(ListenableWorker.Result.success(), h.worker().doWork())
        assertEquals(
            listOf(
                CensusApi.matchedBatchId(listOf(layout to rule, "b".repeat(64) to "doordash.screen.dash_summary")),
                CensusApi.matchedBatchId(listOf(layout to rule)),
                CensusApi.matchedBatchId(listOf("b".repeat(64) to "doordash.screen.dash_summary")),
            ),
            h.api.batches,
        )
        assertTrue(h.api.batches.all { it.startsWith("m-") && it.length <= 64 })
    }
}
