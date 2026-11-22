package cloud.trotter.log.gig.worker

import cloud.trotter.census.contract.CensusPlatforms
import cloud.trotter.log.gig.core.data.census.CensusSpool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import timber.log.Timber
import java.time.LocalDate

/**
 * #1709: the delivery apps one census run may upload for, read from the server's `/v1/policy` (`acceptedPlatforms`).
 * An item of any other app is held: left where it is queued, not sent, not deleted and not counted as rejected, until a
 * later policy lists its app or the usual expiry removes it. A run whose policy has no readable list (the field missing
 * or malformed, or no policy read this run) holds every item. Each held item is counted once per run, per platform
 * wire, for the run's one INFO line.
 */
internal class CensusPlatformHold {
    private var accepted: Set<String>? = null
    private val held = sortedMapOf<String, MutableSet<String>>()
    private var warned = false

    /** A policy read this run; a failed read changes nothing. */
    fun read(policy: JsonObject) {
        accepted = acceptedIn(policy)
    }

    /** True when the policy accepts [platform]. */
    fun sends(platform: String?): Boolean = platform != null && accepted?.contains(platform) == true

    /** The number of distinct items held this run. */
    val heldCount: Int get() = held.values.sumOf { it.size }

    /**
     * True when the run may take [item] of a queue ([stream] keeps the queues' ids apart): the policy accepts its
     * platform, or the run removes it unsent anyway (an app the [covered] choices leave out, or a day before
     * [oldestDay]). Otherwise it is counted as held and stays queued.
     */
    fun admits(item: CensusSpool.Spooled, stream: String, covered: Collection<String>, oldestDay: LocalDate? = null): Boolean {
        val json = Json.parseToJsonElement(item.itemJson).jsonObject
        // An item without a platform is not covered: the run removes it.
        val platform = json["platform"]?.jsonPrimitive?.contentOrNull ?: return true
        if (platform !in covered || sends(platform)) return true
        if (oldestDay != null && LocalDate.parse(json.getValue("day").jsonPrimitive.content) < oldestDay) return true
        hold(platform, "$stream:${item.id}")
        return false
    }

    /** Counts [key] (unique within the run) as held for [platform]. */
    fun hold(platform: String, key: String) {
        held.getOrPut(if (platform in CensusPlatforms.ALL) platform else "other") { mutableSetOf() } += key
    }

    /** The one WARN of a run with no readable list, logged before its first upload stage. */
    fun warnIfClosed(tag: String) {
        if (accepted != null || warned) return
        warned = true
        Timber.tag(tag).w("census policy has no platform list: holding every item this run")
    }

    /** The one INFO line of a run that held items: counts per platform wire only (PII-safe). */
    fun logHeld(tag: String) {
        if (held.isEmpty()) return
        Timber.tag(tag).i("census held %s", held.entries.joinToString(" ") { (wire, keys) -> "$wire=${keys.size}" })
    }

    companion object {
        /** The policy's `acceptedPlatforms`; null when the field is missing, not an array, or holds a non-string. */
        fun acceptedIn(policy: JsonObject): Set<String>? {
            val list = policy["acceptedPlatforms"] as? JsonArray ?: return null
            return list.map { (it as? JsonPrimitive)?.takeIf { entry -> entry.isString }?.content ?: return null }.toSet()
        }
    }
}
