package cloud.trotter.log.gig.domain.census

import cloud.trotter.census.contract.health.CountLevel
import cloud.trotter.census.contract.health.FractionLevel
import cloud.trotter.census.contract.health.HealthReportCheck
import cloud.trotter.census.contract.health.HealthReportSchema
import cloud.trotter.census.contract.health.ShadowDailyDto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** #1630: the row's shadow roster and counters, their load validation, and the `shadowDaily` levels they report. */
class ShadowTallyTest {
    private val key = HealthKey("2026-10-01", "doordash", "7.1.0")
    private val generation = "0123456789abcdef"
    private val rule = "doordash.screen.waiting_for_offer"
    private fun digest(i: Int) = "%016x".format(i)
    private fun result(i: Int, outcome: ShadowOutcome = ShadowOutcome.NoMatch, rule: String = "doordash.screen.c%02d".format(i)) =
        ShadowResult(rule, digest(i), outcome)

    private fun HealthLedger.row(key: HealthKey = this@ShadowTallyTest.key) = rows.getValue(key.toString())

    private fun valid(row: HealthRow): HealthReportCheck.Valid {
        val check = HealthReportSchema.check(Json.parseToJsonElement(HealthLedger.reportJson(key, row, "1.2.3", "dev")))
        assertTrue("$check", check is HealthReportCheck.Valid)
        return check as HealthReportCheck.Valid
    }

    // ── Roster ───────────────────────────────────────────────────────────────────────────────────

    @Test fun `the 65th candidate on a row is not registered and the row is truncated`() {
        val results = (0 until ShadowTally.MAX_SHADOW_ROSTER + 1).map { result(it) }
        val row = HealthLedger().shadowFrame(key, generation, results).row()
        assertEquals(ShadowTally.MAX_SHADOW_ROSTER, row.shadow.size)
        assertFalse(ShadowTally.entryKey(generation, digest(ShadowTally.MAX_SHADOW_ROSTER)) in row.shadow)
        assertTrue(row.shadowTruncated)
    }

    @Test fun `with 40 candidates the wire carries 32 in the fixed order and is marked truncated`() {
        var ledger = HealthLedger().shadowFrame(key, generation, (0 until 40).map { result(it) })
        // Five visits for c39, one for c38: the levels outrank the rule order.
        repeat(5) { ledger = ledger.creditVisit(key, ShadowTally.entryKey(generation, digest(39)), VisitFold(tied = true)) }
        ledger = ledger.creditVisit(key, ShadowTally.entryKey(generation, digest(38)), VisitFold(tied = true))

        val report = valid(ledger.row()).report
        val sent = requireNotNull(report.shadowDaily)
        assertEquals(HealthReportSchema.MAX_SHADOW_ENTRIES, sent.size)
        assertEquals(true, report.shadowDailyTruncated)
        assertEquals(listOf("doordash.screen.c39", "doordash.screen.c38", "doordash.screen.c00"), sent.take(3).map { it.rule })
        assertEquals((0 until 30).map { "doordash.screen.c%02d".format(it) }, sent.drop(2).map { it.rule })
    }

    @Test fun `a version row that never saw a candidate does not list it`() {
        val other = key.copy(platformAppVersion = "7.2.0")
        val ledger = HealthLedger().record(other, rule).shadowFrame(key, generation, listOf(result(1)))
        assertTrue(ledger.row(other).shadow.isEmpty())
        assertNull(valid(ledger.row(other)).report.shadowDaily)
    }

    @Test fun `an unreportable candidate is never registered`() {
        val ledger = HealthLedger()
        assertSame(ledger, ledger.shadowFrame(key, generation, listOf(result(1, rule = "uber.screen.offer"))))
        assertSame(ledger, ledger.shadowFrame(key, generation, listOf(result(1, rule = "Not A Rule"))))
        assertSame(ledger, ledger.shadowFrame(key, "not-hex", listOf(result(1))))
    }

    // ── Counters ─────────────────────────────────────────────────────────────────────────────────

    @Test fun `evaluated counts definite outcomes and saturates at MAX_COUNT`() {
        val matched = ShadowOutcome.Matched(null, MaskScanResult.CLEAN)
        var ledger = HealthLedger()
        for (outcome in listOf(ShadowOutcome.NoMatch, matched, ShadowOutcome.Unevaluated)) {
            ledger = ledger.shadowFrame(key, generation, listOf(result(1, outcome)))
        }
        val entry = ShadowTally.entryKey(generation, digest(1))
        assertEquals(2, ledger.row().shadow.getValue(entry).evaluated)

        val full = ledger.row().let { it.copy(shadow = mapOf(entry to it.shadow.getValue(entry).copy(evaluated = HealthReportSchema.MAX_COUNT))) }
        val saturated = HealthLedger(rows = mapOf(key.toString() to full)).shadowFrame(key, generation, listOf(result(1)))
        assertEquals(HealthReportSchema.MAX_COUNT, saturated.row().shadow.getValue(entry).evaluated)
    }

    @Test fun `an Int overflow makes the entry unavailable and the row omits shadowDaily but keeps base health`() {
        val entry = ShadowTally.entryKey(generation, digest(1))
        val edge = ShadowCounters(rule, visits = Int.MAX_VALUE, tied = Int.MAX_VALUE, onUnknown = 0)
        val row = HealthRow(admitted = 3, ruleCounts = mapOf(rule to 3), shadow = mapOf(entry to edge))
        val ledger = HealthLedger(rows = mapOf(key.toString() to row)).creditVisit(key, entry, VisitFold(tied = true))

        assertTrue(ledger.row().shadow.getValue(entry).unavailable)
        val report = valid(ledger.row()).report
        assertNull(report.shadowDaily)
        assertNull(report.shadowDailyTruncated)
        assertEquals(3, report.admitted)
        assertEquals(mapOf(rule to 3), report.ruleCounts)
    }

    @Test fun `a persisted row that breaks k le n le visits loses its shadow map on load`() {
        val entry = ShadowTally.entryKey(generation, digest(1))
        val good = ShadowCounters(rule, visits = 3, tied = 3, onUnknown = 0, emptyParseN = 2, emptyParseK = 1)
        val row = HealthRow(admitted = 4, ruleCounts = mapOf(rule to 4), shadow = mapOf(entry to good))
        assertSame(row, HealthLedger.withValidShadow(key, row))

        for (broken in listOf(
            good.copy(tied = 4),
            good.copy(emptyParseK = 3),
            good.copy(emptyParseN = 4, emptyParseK = 0),
            good.copy(maskFailN = 1, maskFailK = 2),
            good.copy(visits = -1, tied = 0, emptyParseN = 0, emptyParseK = 0),
            good.copy(rule = "uber.screen.offer"),
        )) {
            val loaded = HealthLedger.withValidShadow(key, row.copy(shadow = mapOf(entry to broken)))
            assertTrue("$broken", loaded.shadow.isEmpty() && loaded.shadowUnavailable)
            assertEquals(4, loaded.admitted)
            assertEquals(mapOf(rule to 4), loaded.ruleCounts)
        }
        val badKey = HealthLedger.withValidShadow(key, row.copy(shadow = mapOf("short:${digest(1)}" to good)))
        assertTrue(badKey.shadowUnavailable)

        // An unavailable row registers nothing more that day and sends no shadowDaily.
        val dropped = HealthLedger.withValidShadow(key, row.copy(shadow = mapOf(entry to good.copy(tied = 4))))
        val ledger = HealthLedger(rows = mapOf(key.toString() to dropped))
        assertSame(ledger, ledger.shadowFrame(key, generation, listOf(result(2))))
        assertNull(valid(dropped).report.shadowDaily)
    }

    // ── Report ───────────────────────────────────────────────────────────────────────────────────

    @Test fun `a visits-0 entry carries only its counts, and a family with n = 0 is left out`() {
        val ledger = HealthLedger().record(key, rule)
            .shadowFrame(key, generation, listOf(result(1, rule = rule), result(2, rule = "doordash.screen.offer")))
            .creditVisit(key, ShadowTally.entryKey(generation, digest(2)), VisitFold(tied = true, maskAssessable = true))

        val sent = requireNotNull(valid(ledger.row()).report.shadowDaily)
        assertEquals(
            listOf(
                ShadowDailyDto(generation, "doordash.screen.offer", digest(2), CountLevel.FEW, CountLevel.FEW,
                    tied = FractionLevel.ALL, onUnknown = FractionLevel.NONE, maskFail = FractionLevel.NONE),
                ShadowDailyDto(generation, rule, digest(1), CountLevel.NONE, CountLevel.FEW),
            ),
            sent,
        )
        val wire = Json.parseToJsonElement(HealthLedger.reportJson(key, ledger.row(), "1.2.3", "dev")).jsonObject
        assertFalse("not truncated: the flag stays off the wire", "shadowDailyTruncated" in wire)
    }

    @Test fun `the levels follow the exact counters`() {
        val entry = ShadowTally.entryKey(generation, digest(1))
        val counters = ShadowCounters(
            rule, visits = 25, evaluated = 400, tied = 25, onUnknown = 3, emptyParseN = 3, emptyParseK = 2, maskFailN = 1, maskFailK = 0,
        )
        val sent = valid(HealthRow(admitted = 400, shadow = mapOf(entry to counters))).report.shadowDaily!!.single()
        assertEquals(
            ShadowDailyDto(generation, rule, digest(1), CountLevel.MANY, CountLevel.MANY, FractionLevel.ALL, FractionLevel.SOME,
                FractionLevel.HALF_OR_MORE, FractionLevel.NONE),
            sent,
        )
    }

    @Test fun `a row with no roster encodes byte for byte as before`() {
        val row = HealthLedger().record(key, rule).row()
        assertEquals(
            """{"day":"2026-10-01","platform":"doordash","platformAppVersion":"7.1.0","appVersion":"1.2.3","rulesetVersion":"dev","admitted":1,"unknown":0,"trips":0,"ruleCounts":{"doordash.screen.waiting_for_offer":1}}""",
            HealthLedger.reportJson(key, row, "1.2.3", "dev"),
        )
    }

    @Test fun `a shadow change bumps the revision so a closed row posts again`() {
        val first = HealthLedger().shadowFrame(key, generation, listOf(result(1)))
        val posted = first.markPosted(key, first.row().revision)
        val again = posted.shadowFrame(key, generation, listOf(result(1)))
        assertEquals(posted.row().revision + 1, again.row().revision)
        assertEquals(listOf(key), again.closedRowsToPost("2026-10-02", "2026-09-25").map { it.first })
    }

    @Test fun `every accepted contract vector the phone could produce comes back out of the ledger`() {
        val vectors = sequenceOf(File("../census-contract/conformance/health-report-vectors.jsonl"),
            File("census-contract/conformance/health-report-vectors.jsonl")).first { it.isFile }.readLines()
        var checked = 0
        for (line in vectors.filter { it.isNotBlank() }) {
            val vector = Json.parseToJsonElement(line).jsonObject
            if (vector.getValue("verdict").jsonPrimitive.content != "accepted") continue
            val expected = (HealthReportSchema.check(vector.getValue("report")) as HealthReportCheck.Valid).report
            val entries = expected.shadowDaily.orEmpty()
            // The phone never sends an empty list, and one generation holds one candidate per digest.
            if (entries.isEmpty() || entries.map { it.generation to it.digest }.toSet().size < entries.size) continue
            val row = HealthRow(
                admitted = expected.admitted,
                unknown = expected.unknown,
                trips = expected.trips,
                ruleCounts = expected.ruleCounts,
                shadow = entries.associate { ShadowTally.entryKey(it.generation, it.digest) to counters(it) },
                shadowTruncated = expected.shadowDailyTruncated == true,
            )
            val vectorKey = HealthKey(expected.day, expected.platform, expected.platformAppVersion)
            val raw = HealthLedger.reportJson(vectorKey, row, expected.appVersion, expected.rulesetVersion, withParseShortfall = false)
            val produced = (HealthReportSchema.check(Json.parseToJsonElement(raw)) as HealthReportCheck.Valid).report
            val name = vector.getValue("name").jsonPrimitive.content
            assertEquals(name, entries.toSet(), produced.shadowDaily.orEmpty().toSet())
            assertEquals(name, expected.shadowDailyTruncated, produced.shadowDailyTruncated)
            checked++
        }
        assertTrue("shadow vectors checked: $checked", checked >= 5)
    }

    /** Exact counters whose levels are [entry]'s. */
    private fun counters(entry: ShadowDailyDto): ShadowCounters {
        val visits = count(entry.visits)
        fun hits(level: FractionLevel?): Int = when (level) {
            null, FractionLevel.NONE -> 0
            FractionLevel.SOME -> 1
            FractionLevel.HALF_OR_MORE -> (visits + 1) / 2
            FractionLevel.ALL -> visits
        }
        return ShadowCounters(
            rule = entry.rule,
            visits = visits,
            evaluated = count(entry.evaluated),
            tied = hits(entry.tied),
            onUnknown = hits(entry.onUnknown),
            emptyParseN = if (entry.emptyParse == null) 0 else visits,
            emptyParseK = hits(entry.emptyParse),
            maskFailN = if (entry.maskFail == null) 0 else visits,
            maskFailK = hits(entry.maskFail),
        )
    }

    private fun count(level: CountLevel): Int = when (level) {
        CountLevel.NONE -> 0
        CountLevel.FEW -> 4
        CountLevel.SEVERAL -> 5
        CountLevel.MANY -> 20
    }
}
