package cloud.trotter.log.gig.core.data.census

import cloud.trotter.log.gig.domain.capture.CensusRecord
import cloud.trotter.log.gig.domain.state.Platform

internal fun censusRecord(number: Int = 0, day: String = "2026-10-02"): CensusRecord {
    val fingerprint = number.toString(16).padStart(64, '0')
    val json = "{\"schemaId\":\"uinode.skeleton.v1\",\"hashDomain\":1,\"filterRev\":1," +
        "\"fingerprint\":\"$fingerprint\",\"platform\":\"doordash\",\"engineVersion\":1," +
        "\"day\":\"$day\",\"root\":{\"class\":\"android.widget.TextView\"}}"
    return CensusRecord(Platform.DoorDash, fingerprint, json, json.toByteArray().size, null)
}

/** Contract-only synthetic notification for transport tests; no pipeline dependency. */
internal fun notificationCensusRecord(): CensusRecord {
    val slots = cloud.trotter.census.contract.NotifTextField.entries.associateWith { cloud.trotter.census.contract.TextSlot.WITHHELD }
    val fingerprint = requireNotNull(cloud.trotter.census.contract.CensusFingerprint.of(Platform.DoorDash.wire, "synthetic", slots))
    val item = cloud.trotter.census.contract.NotificationSkeletonDto(
        kind = cloud.trotter.census.contract.SkeletonKind.NOTIFICATION,
        schemaId = cloud.trotter.census.contract.NotificationSkeletonSchema.SCHEMA_ID,
        hashDomain = 1, filterRev = 1, fingerprint = fingerprint, platform = Platform.DoorDash.wire,
        engineVersion = 1, day = "2026-10-02", channelId = "synthetic", slots = slots,
    )
    val measured = cloud.trotter.census.contract.NotificationSkeletonSchema.measure(item)
    return CensusRecord(Platform.DoorDash, fingerprint, measured.json, measured.bytes, null)
}

/** #1573: a synthetic `uinode.matched.v1` item of rule [ruleId]; [number] picks the layout, as in [censusRecord]. */
internal fun matchedCensusRecord(number: Int = 0, ruleId: String = "doordash.screen.delivery_summary_expanded", day: String = "2026-10-02"): CensusRecord {
    val fingerprint = number.toString(16).padStart(64, '0')
    val json = "{\"schemaId\":\"uinode.matched.v1\",\"hashDomain\":1,\"filterRev\":1," +
        "\"fingerprint\":\"$fingerprint\",\"platform\":\"doordash\",\"engineVersion\":1," +
        "\"day\":\"$day\",\"ruleId\":\"$ruleId\",\"root\":{\"class\":\"android.widget.TextView\"}}"
    return CensusRecord(Platform.DoorDash, fingerprint, json, json.toByteArray().size, null)
}

/**
 * #1634: a synthetic `uinode.clicked.v1` item, serialized by the contract (its canonical bytes): a frame with two buttons,
 * the tap on button [owner]. [number] picks the layout fingerprint as in [censusRecord]; [appVersion] varies the body
 * without changing its identity.
 */
internal fun clickedCensusRecord(
    number: Int = 0,
    owner: Int = 0,
    day: String = "2026-10-02",
    appVersion: String? = null,
): Pair<cloud.trotter.census.contract.UiSkeletonDto, CensusRecord> {
    val fingerprint = number.toString(16).padStart(64, '0')
    val button = cloud.trotter.census.contract.UiSkeletonNodeDto(className = "android.widget.Button", isClickable = true)
    val item = cloud.trotter.census.contract.UiSkeletonDto(
        schemaId = cloud.trotter.census.contract.SkeletonSchema.SCHEMA_ID_CLICKED,
        hashDomain = 1, filterRev = 1, fingerprint = fingerprint, platform = Platform.DoorDash.wire,
        platformAppVersion = appVersion, engineVersion = 1, day = day,
        tap = cloud.trotter.census.contract.TapRef(ownerPath = "root/$owner", generation = "0123456789abcdef"),
        root = cloud.trotter.census.contract.UiSkeletonNodeDto(className = "android.widget.FrameLayout", children = listOf(button, button)),
    )
    val measured = cloud.trotter.census.contract.SkeletonSchema.measure(item)
    return item to CensusRecord(Platform.DoorDash, fingerprint, measured.json, measured.bytes, null)
}
