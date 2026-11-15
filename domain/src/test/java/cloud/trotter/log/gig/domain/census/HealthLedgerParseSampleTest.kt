package cloud.trotter.log.gig.domain.census

import cloud.trotter.census.contract.health.HealthReportCheck
import cloud.trotter.census.contract.health.HealthReportSchema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1571: the per-rule parse sample on a health row, and the report's `parseShortfall` tallies. */
class HealthLedgerParseSampleTest {
    private val key = HealthKey("2026-10-01", "doordash", "7.1.0")
    private val sample = HealthReportSchema.PARSE_SAMPLE
    private val rotting = "doordash.screen.delivery_receipt"
    private val healthy = "doordash.screen.offer_card"
    private val partial = "doordash.screen.dash_summary"

    private fun HealthLedger.attempts(rule: String, vararg shortfalls: Boolean): HealthLedger =
        shortfalls.fold(this) { ledger, shortfall -> ledger.attempt(key, rule, shortfall) }

    private fun HealthLedger.row(): HealthRow = rows.getValue(key.toString())

    @Test fun `a day with a rotting, a healthy and a partial rule reports the full samples only`() {
        val ledger = HealthLedger().record(key, healthy)
            .attempts(rotting, *BooleanArray(sample) { true })
            .attempts(healthy, *BooleanArray(sample + 5) { false })
            .attempts(partial, *BooleanArray(sample - 1) { it % 2 == 0 })
        val row = ledger.row()

        assertEquals(mapOf(rotting to sample, healthy to 0), HealthLedger.parseShortfall(row))

        val raw = HealthLedger.reportJson(key, row, "1.2.3", "dev")
        val check = HealthReportSchema.check(Json.parseToJsonElement(raw))
        assertTrue(check is HealthReportCheck.Valid)
        assertEquals(mapOf(rotting to sample, healthy to 0), (check as HealthReportCheck.Valid).report.parseShortfall)
        val parse = Json.parseToJsonElement(raw).jsonObject.getValue("parseShortfall").jsonObject
        assertEquals("rule ids and integers only", setOf(rotting, healthy), parse.keys)
        assertTrue(parse.values.all { it is JsonPrimitive && !it.isString })
        assertTrue(HealthReportSchema.fits(raw))
    }

    @Test fun `the sample keeps only the last twenty attempts`() {
        val ledger = HealthLedger()
            .attempts(rotting, *BooleanArray(5) { true })
            .attempts(rotting, *BooleanArray(sample) { it >= sample - 3 })
        assertEquals("0".repeat(sample - 3) + "111", ledger.row().parseSamples.getValue(rotting))
        assertEquals(mapOf(rotting to 3), HealthLedger.parseShortfall(ledger.row()))
    }

    @Test fun `a rule with nineteen attempts is left out and no full sample encodes as before`() {
        val ledger = HealthLedger().record(key, healthy).record(key, null).trip(key)
            .attempts(rotting, *BooleanArray(sample - 1) { true })
        val row = ledger.row()
        assertNull(HealthLedger.parseShortfall(row))
        assertEquals(
            """{"day":"2026-10-01","platform":"doordash","platformAppVersion":"7.1.0","appVersion":"1.2.3","rulesetVersion":"dev","admitted":1,"unknown":1,"trips":1,"ruleCounts":{"doordash.screen.offer_card":1}}""",
            HealthLedger.reportJson(key, row, "1.2.3", "dev"),
        )
    }

    @Test fun `every attempt bumps the revision so a changed row posts again`() {
        val posted = HealthLedger().record(key, healthy).let { it.markPosted(key, it.row().revision) }
        val changed = posted.attempt(key, healthy, shortfall = false)
        assertEquals(posted.row().revision + 1, changed.row().revision)
        assertEquals(listOf(key), changed.closedRowsToPost("2026-10-02", "2026-09-25").map { it.first })
    }

    @Test fun `an unreportable rule id is ignored`() {
        val ledger = HealthLedger().record(key, healthy)
        val long = "doordash.screen." + "a".repeat(HealthReportSchema.MAX_PARSE_RULE_ID_LENGTH)
        assertSame(ledger, ledger.attempt(key, long, shortfall = true))
        assertSame(ledger, ledger.attempt(key, "Not A Rule", shortfall = true))
        val atLimit = "doordash.screen." + "a".repeat(HealthReportSchema.MAX_PARSE_RULE_ID_LENGTH - 16)
        assertEquals(1, ledger.attempt(key, atLimit, shortfall = true).row().parseSamples.size)
    }

    @Test fun `a new rule past the per-row cap is ignored while held rules keep sampling`() {
        var ledger = HealthLedger()
        repeat(HealthReportSchema.MAX_PARSE_SHORTFALL_RULES) { ledger = ledger.attempt(key, "doordash.screen.r$it", false) }
        assertSame(ledger, ledger.attempt(key, "doordash.screen.extra", shortfall = true))
        val held = ledger.attempt(key, "doordash.screen.r0", shortfall = true)
        assertEquals("01", held.row().parseSamples.getValue("doordash.screen.r0"))
    }

    @Test fun `a new row past the row cap is refused`() {
        var ledger = HealthLedger()
        repeat(HealthLedger.MAX_ROWS) { ledger = ledger.record(key.copy(platformAppVersion = "$it"), null) }
        assertSame(ledger, ledger.attempt(key.copy(platformAppVersion = "new"), healthy, shortfall = false))
    }

    @Test fun `well-formed samples are exactly what attempt can build`() {
        val built = HealthLedger().attempts(rotting, *BooleanArray(sample + 3) { it % 3 == 0 }).row()
        assertTrue(HealthLedger.wellFormedSamples(built))
        assertTrue(HealthLedger.wellFormedSamples(HealthRow()))
        assertFalse(HealthLedger.wellFormedSamples(HealthRow(parseSamples = mapOf(rotting to "012"))))
        assertFalse(HealthLedger.wellFormedSamples(HealthRow(parseSamples = mapOf(rotting to "0".repeat(sample + 1)))))
        assertFalse(HealthLedger.wellFormedSamples(HealthRow(parseSamples = mapOf(rotting to ""))))
        assertFalse(HealthLedger.wellFormedSamples(HealthRow(parseSamples = mapOf("Bad Rule" to "0"))))
    }

    @Test fun `a report too large with its tallies is sent without them`() {
        // ruleCounts near the byte bound: the report fits alone, not with a full parse map beside it.
        val rules = (0 until 150).associate { "doordash.screen.rule_$it" to 1 }
        var ledger = HealthLedger(mapOf(key.toString() to HealthRow(admitted = rules.size, ruleCounts = rules)))
        for (i in 0 until HealthReportSchema.MAX_PARSE_SHORTFALL_RULES) {
            ledger = ledger.attempts("doordash.screen.parse_rule_$i", *BooleanArray(sample) { false })
        }
        val row = ledger.row()
        assertFalse(HealthReportSchema.fits(HealthLedger.reportJson(key, row, "1.2.3", "dev")))
        val without = HealthLedger.reportJson(key, row, "1.2.3", "dev", withParseShortfall = false)
        assertTrue(HealthReportSchema.fits(without))

        assertEquals(without, HealthLedger.sendableReportJson(key, row, "1.2.3", "dev"))
    }

    @Test fun `a report that fits with its tallies is sent whole, and one too large without them is refused`() {
        val small = HealthLedger().record(key, healthy).attempts(healthy, *BooleanArray(sample) { false }).row()
        assertEquals(HealthLedger.reportJson(key, small, "1.2.3", "dev"), HealthLedger.sendableReportJson(key, small, "1.2.3", "dev"))

        val rules = (1..600).associate { "a.r$it" to 1 }
        val huge = HealthRow(admitted = 600, ruleCounts = rules, parseSamples = mapOf(healthy to "0".repeat(sample)))
        assertNull(HealthLedger.sendableReportJson(key, huge, "1.2.3", "dev"))
    }
}
