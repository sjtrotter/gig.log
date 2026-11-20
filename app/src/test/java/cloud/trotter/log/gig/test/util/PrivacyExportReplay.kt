package cloud.trotter.log.gig.test.util

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetManager
import android.view.accessibility.AccessibilityEvent
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import cloud.trotter.log.gig.core.data.capability.RuleCapabilityRepository
import cloud.trotter.log.gig.core.data.capture.DiskCaptureBus
import cloud.trotter.log.gig.core.data.capture.NoOpCaptureBus
import cloud.trotter.log.gig.core.data.census.*
import cloud.trotter.log.gig.core.data.di.CensusCredentialsModule
import cloud.trotter.log.gig.core.data.log.LogRepository
import cloud.trotter.log.gig.core.data.settings.DevSettingsRepository
import cloud.trotter.log.gig.core.datastore.capability.RuleCapabilityDataSource
import cloud.trotter.log.gig.core.datastore.settings.DevSettingsDataSource
import cloud.trotter.log.gig.core.network.census.CensusApi
import cloud.trotter.log.gig.core.network.census.CensusApiFactory
import cloud.trotter.log.gig.core.network.census.CensusSentObserver
import cloud.trotter.log.gig.core.data.census.CensusDataDeletion
import cloud.trotter.log.gig.core.data.census.CensusSentLog
import cloud.trotter.log.gig.core.network.census.CensusTransport
import cloud.trotter.log.gig.core.network.di.ClientProfile
import cloud.trotter.log.gig.core.network.di.NetworkClientFactory
import cloud.trotter.log.gig.core.pipeline.*
import cloud.trotter.log.gig.core.pipeline.accessibility.AccessibilityPipeline
import cloud.trotter.log.gig.core.pipeline.shadow.ShadowWorker
import cloud.trotter.log.gig.core.pipeline.accessibility.TreeSnapshot
import cloud.trotter.log.gig.core.pipeline.accessibility.event.type.window.content_changed.ContentChangedPipeline
import cloud.trotter.log.gig.core.pipeline.accessibility.event.type.window.state_changed.StateChangedPipeline
import cloud.trotter.log.gig.core.pipeline.accessibility.event.type.window.windows_changed.WindowsChangedPipeline
import cloud.trotter.log.gig.core.pipeline.accessibility.input.AccessibilitySource
import cloud.trotter.log.gig.core.pipeline.accessibility.mapper.toUiNode
import cloud.trotter.log.gig.core.pipeline.census.MatchedSkeletonHold
import cloud.trotter.log.gig.core.pipeline.census.SkeletonPublisher
import cloud.trotter.log.gig.core.pipeline.notification.NotificationFilter
import cloud.trotter.log.gig.core.pipeline.notification.NotificationPipeline
import cloud.trotter.log.gig.core.pipeline.notification.input.NotificationSource
import cloud.trotter.log.gig.core.pipeline.notification.mapper.toDomain
import cloud.trotter.log.gig.core.pipeline.rules.JsonRuleInterpreter
import cloud.trotter.log.gig.di.AppModule
import cloud.trotter.log.gig.domain.capture.*
import cloud.trotter.log.gig.domain.census.CensusUploadScheduler
import cloud.trotter.log.gig.domain.census.CensusUploadStats
import cloud.trotter.log.gig.domain.census.HealthSink
import cloud.trotter.log.gig.domain.model.accessibility.UiNode
import cloud.trotter.log.gig.domain.pipeline.Observation
import cloud.trotter.log.gig.domain.pipeline.RecognitionHealthReporter
import cloud.trotter.log.gig.domain.pipeline.RecognitionNoticeStore
import cloud.trotter.log.gig.domain.settings.GraceConfig
import cloud.trotter.log.gig.domain.settings.PlatformPreferences
import cloud.trotter.log.gig.domain.state.Platform
import cloud.trotter.log.gig.log.StateAwareTree
import cloud.trotter.log.gig.worker.CensusUploadWorker
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.serialization.json.*
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Provider
import kotlin.coroutines.CoroutineContext

/**
 * #1271 scenario 6 — real sensor admission → capture → census files → worker → signed bytes,
 * with the real INFO+ file sink beside it. No state/effect/Room graph belongs in this sibling.
 * Storage, Android service/nodes, metadata, Keystore and terminal HTTP are the external edges.
 * This does not exercise Android event-delivery consent, release Hilt wiring or screenshot pixels.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PrivacyExportReplay(
    private val context: Context,
    private val test: TestScope,
    captures: Boolean = true,
    census: Boolean = true,
    debug: Boolean = true,
) : AutoCloseable {
    private val io = OwnedDispatcher(StandardTestDispatcher(test.testScheduler))
    private val backgroundScope = CoroutineScope(test.backgroundScope.coroutineContext +
        SupervisorJob(test.backgroundScope.coroutineContext[Job]) + io)
    /**
     * #1573: the frame clock, read by the classifier and the accessibility event stamp. It follows the system clock until
     * [frame]'s `at` fixes it at an offset from the harness's start, so frames fall on the upload's own UTC day.
     */
    val clock = ReplayClock(System.currentTimeMillis())
    val uploadStats = CensusUploadStats()
    val stats = PipelineStats("privacy-e2e", uploadStats)
    val preferences = DevSettingsRepository(DevSettingsDataSource(ReplayEdges.MemoryPreferences()), debug, io, "test")
    val skeletonSpool = CensusSpool(context, uploadStats, io)
    /** #1634: the click evidence store beside the skeleton spool, as wired. */
    val clicks = CensusClickCandidates(skeletonSpool, uploadStats)
    val envelopeSpool = CensusCredentialsModule.envelopeSpool(context, uploadStats, io)
    val skeletonRoot = File(context.filesDir, "census/spool")
    val envelopeRoot = File(context.filesDir, "census/envelopes")
    val localRoot: File = context.getExternalFilesDir(null) ?: context.filesDir
    val captureRoot = File(localRoot, "captures")
    val log = LogRepository(context, io, AppModule.provideLogScrubber())
    private val tree = StateAwareTree(log, preferences, Provider { "IDLE" })
    val credentials = CensusCredentialStore(ReplayEdges.MemoryPreferences(), object : KeystoreSealer() {
        override fun seal(bytes: ByteArray) = Sealed(byteArrayOf(1), xor(bytes))
        override fun open(iv: ByteArray, ct: ByteArray) = xor(ct)
        override fun reset() = Unit
        private fun xor(bytes: ByteArray) = bytes.map { (it.toInt() xor 85).toByte() }.toByteArray()
    })
    val scheduling = RecordingScheduler()
    // As built: only the debug build binds the trusted-envelope sink; every build binds the skeleton sink (#1620).
    private val envelopeSink: CensusEnvelopeSink = if (census && debug)
        PersistentCensusEnvelopeSink(preferences, envelopeSpool, uploadStats, io, readsUnboundPlatforms = true)
        else NoOpCensusEnvelopeSink
    private val sink: CensusSink = if (census)
        HttpCensusSink(preferences, skeletonSpool, CensusDailySample(context, io), uploadStats, backgroundScope, io,
            readsUnboundPlatforms = true, matchedCap = CensusMatchedCap(context, io), clicks = clicks) else object : CensusSink {
            override val isEnabled = false
            override fun offer(record: CensusRecord) = false
        }
    private val bus: CaptureBus = if (captures) DiskCaptureBus(context, io, envelopeSink, uploadStats) else NoOpCaptureBus()
    private val health = PersistentHealthSink(HealthLedgerStore(ReplayEdges.MemoryPreferences(), uploadStats), uploadStats, backgroundScope, io)
    private val metadata = object : ReplayMetadataProvider {
        override fun current() = ReplayMetadata(engineVersion = 1, appVersion = "privacy-e2e", rulesetReleaseTag = "dev",
            deviceFingerprint = "FAKEDEVICECANARY", rulesetSignature = "FAKESIGNATURECANARY")
    }
    private val interpreter = JsonRuleInterpreter(generatedAssets(context), RuleCapabilityRepository(
        RuleCapabilityDataSource(ReplayEdges.MemoryPreferences()), backgroundScope, "privacy-e2e", Clock.systemUTC()))
    private val classifier = ObservationClassifier(interpreter, metadata, PlatformAppVersions.NONE, clock)
    private val platforms = object : PlatformPreferences {
        override val enabledPlatforms = MutableStateFlow(setOf(Platform.DoorDash))
        override val enabledPackages = MutableStateFlow(setOf(PrivacyExportInputs.PACKAGE))
        override val graceConfig = MutableStateFlow(emptyMap<Platform, GraceConfig>())
    }
    private val source = AccessibilitySource(stats)
    private val service = mock<AccessibilityService>()
    private val notifications = NotificationSource()
    private val writer = CaptureWriter(bus, stats)
    // #1573: as wired: the monitor owns the incidents and tells the one hold of each confirmed rot visit.
    private val hold = MatchedSkeletonHold(sink, stats)
    private val monitor = RecognitionHealthMonitor(RecognitionHealthReporter.NoOp, health, RecognitionNoticeStore.NoOp,
        backgroundScope, io, hold)
    private val publisher = SkeletonPublisher(sink, stats, envelopeSink, monitor, hold)
    private val accessibility = AccessibilityPipeline(ContentChangedPipeline(source, platforms, stats),
        StateChangedPipeline(source, platforms, stats), WindowsChangedPipeline(source, platforms, stats),
        source, classifier, writer, platforms, stats, monitor, publisher,
        // #1630: shadow outcomes stay out of this harness's health rows, which the worker thread would make timing-bound.
        ShadowWorker(HealthSink.NoOp, stats), clock)
    private val notificationPipeline = NotificationPipeline(notifications, NotificationFilter(platforms), classifier, writer, platforms, stats, publisher)
    val forwarded = mutableListOf<Observation>()
    val requests = CopyOnWriteArrayList<RequestBytes>()
    private val unexpectedRoutes = CopyOnWriteArrayList<String>()
    var revokeNextSkeleton = false
    /** What the fake server's policy (and enrol reply) advertises; #1522 narrows it to screens only. */
    var acceptedSchemaIds: List<String> = listOf("uinode.skeleton.v1", "notification.skeleton.v1")
    var factoryCalls = 0
        private set
    private val client = NetworkClientFactory.okHttpClient("Census", ClientProfile.Census).newBuilder()
        .addInterceptor { chain ->
            val request = chain.request()
            val buffer = Buffer()
            request.body?.writeTo(buffer)
            val body = buffer.readByteString()
            val path = request.url.encodedPath
            val root = when (path) { "/v1/skeletons" -> skeletonRoot; "/v1/envelopes" -> envelopeRoot; else -> null }
            val marker = root?.let { File(if (it == skeletonRoot) it.parentFile else it, "inflight.json") }
            requests += RequestBytes(request.method, path, request.headers, body,
                marker?.takeIf { it.isFile }?.readBytes()?.toByteString(), root?.let(::records).orEmpty())
            var status = 200
            val response = when (path) {
                "/v1/enroll", "/v1/policy" -> """{"acceptedSchemaIds":${JsonArray(acceptedSchemaIds.map { JsonPrimitive(it) })}}"""
                "/v1/skeletons", "/v1/envelopes" -> if (path == "/v1/skeletons" && revokeNextSkeleton) {
                    revokeNextSkeleton = false
                    status = 401
                    """{"error":"revoked"}"""
                } else {
                    val count = privacyExportDecode("request$path") {
                        Json.parseToJsonElement(body.utf8()).jsonObject.getValue("items").jsonArray.size
                    }
                    """{"status":"accepted","accepted":$count,"duplicate":0,"rejected":{},"budget":{"skeletonsRemainingToday":999,"bytesRemainingToday":9000000,"batchesRemainingToday":99,"resetInSeconds":3600}}"""
                }
                else -> { unexpectedRoutes += path; throw IOException("unexpected census route") }
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("scripted")
                .body(response.toResponseBody("application/json".toMediaType())).build()
        }.build()
    /** #1622: the production sent record, fed by the production transport's own bytes. */
    val sentLog = CensusSentLog(context, io)
    val sentFile: File get() = File(context.filesDir, "census/sent.json")
    private val apiFactory = object : CensusApiFactory(CensusSentObserver(sentLog::record)) {
        override fun create(baseUrl: String): CensusTransport {
            factoryCalls++
            return CensusApi(client, "https://census.invalid", CensusSentObserver(sentLog::record))
        }
    }
    private val lock = CensusUploadLock()
    private val deletion = CensusDataDeletion(credentials, skeletonSpool, envelopeSink, health, preferences, scheduling, lock,
        apiFactory, backgroundScope, "privacy-e2e")

    /** Load unchanged generated assets before starting the only collectors of the production outputs. */
    suspend fun start() {
        assertTrue("fresh capture storage", captures().isEmpty())
        assertTrue("fresh skeleton storage", records(skeletonRoot).isEmpty())
        assertTrue("fresh envelope storage", records(envelopeRoot).isEmpty())
        assertTrue("fresh shareable log", !File(localRoot, "shareable.log").exists())
        Timber.plant(tree)
        whenever(service.packageName).thenReturn(context.packageName)
        whenever(service.resources).thenReturn(context.resources)
        whenever(service.windows).thenReturn(emptyList())
        source.registerService(service)
        interpreter.loadDefaults()
        assertTrue("production rule loader ready", interpreter.isLoaded)
        backgroundScope.launch { accessibility.output().collect { forwarded += it } }
        backgroundScope.launch { notificationPipeline.output().collect { forwarded += it } }
        settle()
    }

    fun settle() = test.testScheduler.runCurrent()

    /** Preflight classification checks the splice; only emit(event) drives persistence and forwarding. */
    fun screen(kind: PrivacyExportInputs.Screen, node: UiNode = PrivacyExportInputs.screen(kind)) {
        val before = fileCounts()
        frame(node, kind.target, at = null) { "mutated input classification: $kind" }
        if (kind in setOf(PrivacyExportInputs.Screen.BANKING, PrivacyExportInputs.Screen.BANKING_UNKNOWN)) {
            assertEquals("sensitive screen creates no capture or census record", before, fileCounts())
        }
    }

    /**
     * #1573: one screen frame [node], which must classify as [target], at frame time [at] ms after the harness started
     * (null keeps the clock where it is). Returns the preflight classification.
     */
    @Suppress("DEPRECATION") // obtain/recycle match the framework callback lifetime on SDK 35.
    fun frame(node: UiNode, target: String, at: Long?, label: () -> String = { "input classification" }): Observation.Screen {
        at?.let { clock.at(it) }
        val native = PrivacyExportInputs.mirror(node)
        val mapped = requireNotNull(native.toUiNode())
        assertTrue("native adapter preserves the input tree", PrivacyExportInputs.nodes(node) == PrivacyExportInputs.nodes(mapped))
        val preflight = classifier.classify(PipelineEvent.Screen(clock.millis(), mapped,
            TreeSnapshot(mapped, PrivacyExportInputs.PACKAGE), PrivacyExportInputs.PACKAGE))
        assertEquals(label(), target, preflight.target)
        whenever(service.rootInActiveWindow).thenReturn(native)
        val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED).apply {
            packageName = PrivacyExportInputs.PACKAGE
            className = "android.widget.FrameLayout"
        }
        try { source.emit(event); settle() } finally { event.recycle() }
        return preflight
    }

    fun notification(kind: PrivacyExportInputs.Push) {
        val before = fileCounts()
        val notification = PrivacyExportInputs.notification(context, kind, System.currentTimeMillis())
        val raw = requireNotNull(notification.toDomain())
        assertEquals("notification splice stays UNKNOWN: $kind", "UNKNOWN",
            classifier.classify(PipelineEvent.Notification(raw.postTime, raw)).target)
        notifications.emit(notification)
        settle()
        if (kind == PrivacyExportInputs.Push.BANKING) assertEquals("sensitive action creates no capture or census record", before, fileCounts())
    }

    /** Immediate repetitions must be suppressed before capture and census publication. */
    fun manifest(repeatBenign: Boolean = false) {
        for (kind in PrivacyExportInputs.Screen.entries) {
            screen(kind)
            if (repeatBenign && kind == PrivacyExportInputs.Screen.UNKNOWN) duplicate { screen(kind) }
        }
        for (kind in PrivacyExportInputs.Push.entries) {
            notification(kind)
            if (repeatBenign && kind == PrivacyExportInputs.Push.BENIGN) duplicate { notification(kind) }
        }
    }
    private fun duplicate(emit: () -> Unit) {
        val before = fileCounts()
        val suppressed = stats.suppressedDuplicateCount
        emit()
        assertEquals("repeat adds no files", before, fileCounts())
        assertEquals("admission counted duplicate", suppressed + 1, stats.suppressedDuplicateCount)
    }
    private fun fileCounts() = listOf(captures().size, records(skeletonRoot).size, records(envelopeRoot).size)

    /** Genuine WorkerParameters, production worker, real callback completion; never advanceUntilIdle. */
    suspend fun worker() {
        val worker = TestListenableWorkerBuilder<CensusUploadWorker>(context)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
                    CensusUploadWorker(appContext, workerParameters, preferences, credentials, skeletonSpool, apiFactory,
                        CensusUploadPlanner(preferences, scheduling, Clock.systemUTC()), unmetered, uploadStats, lock, health,
                        metadata,
                        envelopeSpool, envelopeSink, deletion, clicks)
            }).build()
        assertEquals("worker completes", ListenableWorker.Result.success(), worker.doWork())
        settle()
        assertTrue("unexpected HTTP route: $unexpectedRoutes", unexpectedRoutes.isEmpty())
    }

    fun captures(): List<File> = captureRoot.walkTopDown().filter { it.isFile }.toList()
    fun shareable(): ByteArray = File(localRoot, "shareable.log").takeIf { it.isFile }?.readBytes() ?: byteArrayOf()

    /** Immutable request and in-flight file snapshots survive the worker's acknowledgement/removal. */
    data class RequestBytes(val method: String, val path: String, val headers: Headers, val body: ByteString,
        val marker: ByteString?, val queued: List<Stored>)
    data class Stored(val name: String, val bytes: ByteString) {
        override fun toString(): String = "Stored($name, [bytes omitted])"
        val wrapper: JsonObject get() = privacyExportDecode("stored/$name") {
            Json.parseToJsonElement(bytes.utf8()).jsonObject
        }
        // Mirror the spool's byte-preserving wrapper delimiters, not a JSON reserialization.
        val itemJson: String get() = bytes.utf8().substringAfter("\"skeleton\":").substringBeforeLast(",\"captureId\":")
    }
    fun records(root: File): List<Stored> = root.listFiles().orEmpty()
        .filter { it.isFile && it.name.matches(Regex("[0-9]{1,19}-[0-9]{1,19}\\.json")) }
        .sortedBy { it.name }.map { Stored(it.name, it.readBytes().toByteString()) }

    /** The replay's network is Wi-Fi: the census's Wi-Fi only check passes. */
    private val unmetered = object : CensusNetwork(context) {
        override fun isMetered() = false
    }

    /** #1573: the system clock until [at] fixes it at [start] plus an offset. */
    class ReplayClock(private val start: Long) : Clock() {
        @Volatile private var fixed: Long? = null
        fun at(offset: Long) { fixed = start + offset }
        override fun millis(): Long = fixed ?: System.currentTimeMillis()
        override fun instant(): Instant = Instant.ofEpochMilli(millis())
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
    }

    class RecordingScheduler : CensusUploadScheduler {
        val requests = mutableListOf<String>()
        override fun enqueueNow(wifiOnly: Boolean, replaceQueued: Boolean) { requests += "now:$wifiOnly:$replaceQueued" }
        override fun scheduleDaily(runAtMillis: Long, wifiOnly: Boolean) { requests += "daily:$runAtMillis:$wifiOnly" }
        override fun cancelManual() { requests += "cancel" }
        override fun enqueueDeletion() { requests += "delete" }
    }

    /** Drain writes before cancellation, including private production IO consumers, then release HTTP. */
    override fun close() {
        settle()
        Timber.uproot(tree)
        backgroundScope.cancel()
        io.cancelOwnedJobs()
        settle()
        client.dispatcher.cancelAll()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    /** Track IO jobs without replacing production scopes or adding a production teardown seam. */
    @OptIn(InternalCoroutinesApi::class)
    private class OwnedDispatcher(private val delegate: TestDispatcher) : CoroutineDispatcher(), Delay by delegate {
        private val jobs = ConcurrentHashMap.newKeySet<Job>()
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            context[Job]?.let { jobs += it }
            delegate.dispatch(context, block)
        }
        fun cancelOwnedJobs() = jobs.forEach { it.cancel() }
    }

    private fun generatedAssets(app: Context): Context {
        val assets = TestRulesetFactory.generatedAssets()
        return object : ContextWrapper(app) { override fun getAssets(): AssetManager = assets }
    }
}

/** Captured content must never escape through parser messages or exception causes. */
internal fun <T> privacyExportDecode(surface: String, decode: () -> T): T = try {
    decode()
} catch (_: Exception) {
    throw AssertionError("$surface: unparseable")
}
