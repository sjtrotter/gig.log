package cloud.trotter.log.gig.core.data.fuel

import cloud.trotter.feed.contract.FeedFiles
import cloud.trotter.feed.contract.FeedResult
import cloud.trotter.feed.contract.VerifiedFeedFile
import cloud.trotter.log.gig.core.data.feed.FeedStage
import cloud.trotter.log.gig.core.data.feed.FeedStager
import cloud.trotter.log.gig.domain.fuel.FuelAcceptance
import cloud.trotter.log.gig.domain.fuel.FuelAnchor
import cloud.trotter.log.gig.domain.fuel.FuelArea
import cloud.trotter.log.gig.domain.fuel.FuelCarry
import cloud.trotter.log.gig.domain.fuel.FuelKey
import cloud.trotter.log.gig.domain.fuel.FuelPayload
import cloud.trotter.log.gig.domain.fuel.FuelPriceAcceptance
import cloud.trotter.log.gig.domain.fuel.FuelPrices
import cloud.trotter.log.gig.domain.model.vehicle.FuelType
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.math.BigDecimal

/**
 * The fuel feed's stage check (contract 1, `docs/feeds/FORMAT.md` § Fuel payload). It owns only JSON to and from the
 * domain: [FuelPriceAcceptance] decides every bound. Unknown area codes and fuel keys are ignored, and a pair whose
 * value is not a JSON number is omitted. Prices become decimals from the number's own text, never through a `Double`.
 *
 * The carry is the accepted week and its anchors, with prices as decimal strings:
 * `{"format":1,"week":{"period":…,"prices":{code:{fuel:"3.051"}}},"anchors":{code:{fuel:{"period":…,"price":"3.051"}}}}`.
 * A carry that cannot be read counts as none, so only the absolute range applies.
 */
class FuelFeedStager : FeedStager<FuelPrices> {
    override val contracts: Set<Long> = setOf(1L)

    override fun stage(files: List<VerifiedFeedFile>, carry: JsonObject?): FeedStage<FuelPrices> =
        check(files) { payload, issuedAt -> FuelPriceAcceptance.stage(payload, issuedAt, FuelCarryJson.decode(carry)) }

    override fun restore(files: List<VerifiedFeedFile>, carry: JsonObject?): FeedStage<FuelPrices> =
        check(files) { payload, issuedAt -> FuelPriceAcceptance.restore(payload, issuedAt, FuelCarryJson.decode(carry)) }

    /** Reads the one payload file and runs [accept] on it with the generation's `issued_at` (its sequence). */
    private fun check(files: List<VerifiedFeedFile>, accept: (FuelPayload, Long) -> FuelAcceptance): FeedStage<FuelPrices> {
        val file = files.singleOrNull() ?: return FeedStage.Rejected(MALFORMED)
        return when (val read = readPayload(file)) {
            is Read.Ok -> ready(accept(read.payload, file.sequence))
            is Read.Refused -> FeedStage.Rejected(read.reason)
        }
    }

    private fun ready(acceptance: FuelAcceptance): FeedStage<FuelPrices> = when (acceptance) {
        is FuelAcceptance.Ready ->
            FeedStage.Ready(acceptance.prices, acceptance.prices.period, FuelCarryJson.encode(acceptance.carry))
        is FuelAcceptance.Rejected -> FeedStage.Rejected(acceptance.reason)
    }

    private sealed interface Read {
        class Ok(val payload: FuelPayload) : Read
        class Refused(val reason: String) : Read
    }

    private fun readPayload(file: VerifiedFeedFile): Read {
        val root = (FeedFiles.parsePayload(file) as? FeedResult.Ok)?.value as? JsonObject ?: return Read.Refused(MALFORMED)
        val period = (root["period"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
            ?: return Read.Refused(MALFORMED)
        val areas = root["prices"] as? JsonObject ?: return Read.Refused(MALFORMED)
        if (areas.values.any { it !is JsonObject }) return Read.Refused(MALFORMED)
        val unit = (root["unit"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (unit != UNIT) return Read.Refused("wrong_unit")
        val source = (root["source"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (source != SOURCE) return Read.Refused("wrong_source")

        val prices = LinkedHashMap<FuelArea, Map<FuelType, BigDecimal>>()
        for ((code, value) in areas) {
            val area = FuelArea.fromCode(code) ?: continue
            val fuels = LinkedHashMap<FuelType, BigDecimal>()
            for ((key, price) in value.jsonObject) {
                val fuel = FuelKey.fuelOf(key) ?: continue
                fuels[fuel] = number(price) ?: continue
            }
            if (fuels.isNotEmpty()) prices[area] = fuels
        }
        return Read.Ok(FuelPayload(period, prices))
    }

    /** The decimal of a JSON number's text; null for a string, `null`, a boolean or anything not a number. */
    private fun number(element: JsonElement): BigDecimal? {
        val primitive = element as? JsonPrimitive ?: return null
        if (primitive.isString) return null
        return primitive.content.toBigDecimalOrNull()
    }

    private companion object {
        const val MALFORMED = "malformed"
        const val UNIT = "USD/gal"
        const val SOURCE = "EIA"
    }
}

/** The fuel carry as JSON. [decode] never throws: an unreadable carry, or one of another format, is null. */
internal object FuelCarryJson {
    private const val FORMAT = 1

    fun encode(carry: FuelCarry): JsonObject = buildJsonObject {
        put("format", FORMAT)
        put("week", buildJsonObject {
            put("period", carry.week.period)
            put("prices", byArea(carry.week.prices) { price -> JsonPrimitive(price.toPlainString()) })
        })
        put("anchors", byArea(carry.anchors) { anchor ->
            buildJsonObject {
                put("period", anchor.period)
                put("price", anchor.price.toPlainString())
            }
        })
    }

    fun decode(carry: JsonObject?): FuelCarry? {
        carry ?: return null
        return try {
            if (carry["format"]?.jsonPrimitive?.longOrNull != FORMAT.toLong()) return null
            val week = carry.getValue("week").jsonObject
            val weekPrices = byCodes(week.getValue("prices").jsonObject) { decimal(it) }
            val anchors = byCodes(carry.getValue("anchors").jsonObject) { element ->
                val anchor = element.jsonObject
                FuelAnchor(anchor.getValue("period").jsonPrimitive.long, decimal(anchor.getValue("price")))
            }
            FuelCarry(FuelPayload(week.getValue("period").jsonPrimitive.long, weekPrices), anchors)
        } catch (_: Exception) {
            null
        }
    }

    private fun <V> byArea(map: Map<FuelArea, Map<FuelType, V>>, value: (V) -> JsonElement) =
        JsonObject(
            map.entries.associate { (area, fuels) ->
                area.code to JsonObject(
                    fuels.entries.mapNotNull { (fuel, v) -> FuelKey.of(fuel)?.let { it to value(v) } }.toMap(),
                )
            },
        )

    private fun <V> byCodes(json: JsonObject, value: (JsonElement) -> V): Map<FuelArea, Map<FuelType, V>> {
        val out = LinkedHashMap<FuelArea, Map<FuelType, V>>()
        for ((code, fuels) in json) {
            val area = FuelArea.fromCode(code) ?: continue
            val byFuel = LinkedHashMap<FuelType, V>()
            for ((key, v) in fuels.jsonObject) {
                val fuel = FuelKey.fuelOf(key) ?: continue
                byFuel[fuel] = value(v)
            }
            if (byFuel.isNotEmpty()) out[area] = byFuel
        }
        return out
    }

    /** A carried price: always a decimal string, so no `Double` round trip touches it. */
    private fun decimal(element: JsonElement): BigDecimal {
        val primitive = element.jsonPrimitive
        require(primitive.isString)
        return BigDecimal(primitive.content)
    }
}
