package cloud.trotter.log.gig.core.data.fuel

import cloud.trotter.feed.contract.Feed
import cloud.trotter.feed.contract.VerifiedFeedFile
import cloud.trotter.log.gig.core.data.feed.FeedStage
import cloud.trotter.log.gig.core.data.feed.SignedSource
import cloud.trotter.log.gig.domain.fuel.FuelAnchor
import cloud.trotter.log.gig.domain.fuel.FuelArea
import cloud.trotter.log.gig.domain.fuel.FuelCarry
import cloud.trotter.log.gig.domain.fuel.FuelPayload
import cloud.trotter.log.gig.domain.fuel.FuelPrices
import cloud.trotter.log.gig.domain.model.vehicle.FuelType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/** #1566: the fuel stager reads the publisher's payload shape and carries its anchors through `meta.json`. */
class FuelFeedStagerTest {
    private val day = 24L * 60 * 60 * 1000

    /** 2026-11-16T00:00Z, a Monday: the publisher's vector week. */
    private val period = 1_794_787_200_000L
    private val source = SignedSource(Feed.FUEL)
    private val stager = FuelFeedStager()

    /** The publisher's vector payload (`feed-contract/src/test/resources/vectors/fuel/078d1f5e….json`). */
    private val vector = """{"period":1794787200000,"prices":{"NUS":{"regular":3.051},"SCA":{"diesel":5.402}},""" +
        """"series":{"diesel":"EMD_EPD2D_PTE","midgrade":"EMM_EPMM_PTE","premium":"EMM_EPMP_PTE","regular":"EMM_EPMR_PTE"},""" +
        """"source":"EIA","unit":"USD/gal"}"""

    private fun payload(
        prices: String,
        period: Long = this.period,
        unit: String = "\"USD/gal\"",
        source: String = "\"EIA\"",
    ): String = """{"period":$period,"prices":$prices,"source":$source,"unit":$unit}"""

    private fun files(json: String, issuedAt: Long = period + 2 * day): List<VerifiedFeedFile> =
        source.verified(source.generation(issuedAt, json.toByteArray()))

    private fun ready(stage: FeedStage<FuelPrices>): FeedStage.Ready<FuelPrices> =
        stage as? FeedStage.Ready ?: throw AssertionError("expected Ready, was $stage")

    private fun rejected(stage: FeedStage<FuelPrices>): String = (stage as FeedStage.Rejected).reason

    @Test fun `the publisher's vector stages its two prices`() {
        val staged = ready(stager.stage(files(vector), carry = null))
        assertEquals(period, staged.period)
        assertEquals(period, staged.value.period)
        assertEquals(3.051, staged.value.prices[FuelArea.US]?.get(FuelType.REGULAR))
        assertEquals(5.402, staged.value.prices[FuelArea.CALIFORNIA]?.get(FuelType.DIESEL))
        assertEquals(2, staged.value.prices.values.sumOf { it.size })
    }

    @Test fun `a quoted price is omitted, and the rest of the area stays`() {
        val staged = ready(stager.stage(files(payload("""{"NUS":{"regular":"3.051","midgrade":3.2}}""")), carry = null))
        assertEquals(mapOf(FuelType.MIDGRADE to 3.2), staged.value.prices[FuelArea.US])
    }

    @Test fun `a null or boolean price is omitted`() {
        val staged = ready(stager.stage(files(payload("""{"NUS":{"regular":null,"premium":true,"diesel":3.7}}""")), carry = null))
        assertEquals(mapOf(FuelType.DIESEL to 3.7), staged.value.prices[FuelArea.US])
    }

    @Test fun `unknown area codes and fuel keys are ignored`() {
        val staged = ready(stager.stage(files(payload("""{"SZZ":{"regular":3.0},"NUS":{"kerosene":4.0,"regular":3.0}}""")), carry = null))
        assertEquals(mapOf(FuelArea.US to mapOf(FuelType.REGULAR to 3.0)), staged.value.prices)
    }

    @Test fun `a payload without its period, unit or source is refused`() {
        assertEquals("malformed", rejected(stager.stage(files("""{"prices":{},"source":"EIA","unit":"USD/gal"}"""), carry = null)))
        assertEquals("malformed", rejected(stager.stage(files("""{"period":"x","prices":{},"source":"EIA","unit":"USD/gal"}"""), carry = null)))
        assertEquals("malformed", rejected(stager.stage(files(payload("""{"NUS":3.0}""")), carry = null)))
        assertEquals("malformed", rejected(stager.stage(files("""[1]"""), carry = null)))
        assertEquals("wrong_unit", rejected(stager.stage(files(payload("{}", unit = "\"USD/L\"")), carry = null)))
        assertEquals("wrong_unit", rejected(stager.stage(files("""{"period":$period,"prices":{},"source":"EIA"}"""), carry = null)))
        assertEquals("wrong_source", rejected(stager.stage(files(payload("{}", source = "\"OPIS\"")), carry = null)))
    }

    @Test fun `a period that is not a Monday refuses the generation`() {
        assertEquals("period_not_monday", rejected(stager.stage(files(payload("{}", period = period + day)), carry = null)))
    }

    @Test fun `the carry round-trips through JSON with prices as decimal text`() {
        val first = ready(stager.stage(files(vector), carry = null))
        val carry = requireNotNull(first.carry)
        val week = carry.getValue("week").jsonObject
        assertEquals("\"3.051\"", week.getValue("prices").jsonObject.getValue("NUS").jsonObject.getValue("regular").toString())

        val decoded = requireNotNull(FuelCarryJson.decode(carry))
        val expected = FuelCarry(
            FuelPayload(
                period,
                mapOf(
                    FuelArea.US to mapOf(FuelType.REGULAR to BigDecimal("3.051")),
                    FuelArea.CALIFORNIA to mapOf(FuelType.DIESEL to BigDecimal("5.402")),
                ),
            ),
            anchors = emptyMap(),
        )
        assertEquals(expected, decoded)
        assertEquals(carry, FuelCarryJson.encode(decoded))

        val next = ready(stager.stage(files(payload("""{"NUS":{"regular":3.10}}""", period = period + 7 * day), issuedAt = period + 9 * day), carry))
        val anchors = requireNotNull(FuelCarryJson.decode(next.carry)).anchors
        assertEquals(FuelAnchor(period, BigDecimal("3.051")), anchors[FuelArea.US]?.get(FuelType.REGULAR))
        assertEquals(FuelAnchor(period, BigDecimal("5.402")), anchors[FuelArea.CALIFORNIA]?.get(FuelType.DIESEL))
    }

    @Test fun `restore from the committed carry gives what stage gave`() {
        val live = ready(stager.stage(files(vector), carry = null)).carry
        val nextFiles = files(payload("""{"NUS":{"regular":9.00,"midgrade":3.4}}""", period = period + 7 * day), issuedAt = period + 9 * day)
        val staged = ready(stager.stage(nextFiles, live))
        assertEquals(staged, stager.restore(nextFiles, staged.carry))
    }

    @Test fun `an unreadable carry stages with the absolute range only`() {
        val live = ready(stager.stage(files(payload("""{"NUS":{"regular":3.00}}""")), carry = null)).carry
        val next = files(payload("""{"NUS":{"regular":9.00}}""", period = period + 7 * day), issuedAt = period + 9 * day)
        assertTrue("a valid carry bounds the move", ready(stager.stage(next, live)).value.prices.isEmpty())

        for (bad in listOf("""{"format":2}""", """{"format":1,"week":"x"}""", """{"format":1}""", """{"week":{}}""")) {
            val carry = Json.parseToJsonElement(bad) as JsonObject
            assertNull(bad, FuelCarryJson.decode(carry))
            assertEquals(bad, 9.00, ready(stager.stage(next, carry)).value.prices[FuelArea.US]?.get(FuelType.REGULAR))
        }
    }
}
