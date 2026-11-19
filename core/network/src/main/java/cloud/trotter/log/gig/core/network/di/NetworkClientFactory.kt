package cloud.trotter.log.gig.core.network.di

import cloud.trotter.census.contract.auth.CensusHeaders
import cloud.trotter.log.gig.core.network.BuildConfig
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Census logs headers only and connects in ten seconds. Feed logs nothing over HTTP (#1565): a request line carries the
 * source's URL, and a custom source's URL is the dasher's choice.
 */
enum class ClientProfile { Default, Census, Feed }

/**
 * Single source of truth for Retrofit/OkHttp client assembly across [core:network].
 *
 * Before this existed, every API module hand-rolled its own [OkHttpClient] +
 * [Retrofit.Builder], and the two copies had already diverged on the #348 security
 * hardening (one redacted the `api_key` query param and set timeouts, the other did
 * neither). Centralizing the assembly here makes the secure path the *default* path:
 * any provider that calls [okHttpClient] gets debug-gated, Timber-piped logging (Census headers only, Feed nothing, other clients BODY)
 * **with secret redaction and standard timeouts baked in** — there is no longer a
 * per-module step to remember.
 *
 * Providers pass only what differs (base URL, logging tag, and — if the upstream
 * carries secrets in additional query params — extra param names to redact).
 */
object NetworkClientFactory {

    private const val DEFAULT_TIMEOUT_SECONDS = 30L

    /**
     * Query parameters redacted from HTTP logs by default (#348). No current upstream carries a
     * secret in its query; `api_key`, the common name for one, stays redacted so a new caller
     * that adds such a key cannot leak it to logcat or the persisted file log.
     */
    private val DEFAULT_REDACTED_QUERY_PARAMS = listOf("api_key")

    /**
     * Lenient JSON for government APIs whose schemas we do not control: ignore unknown
     * keys, tolerate loose typing, coerce nulls into defaults.
     */
    val govApiJson: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /**
     * A [OkHttpClient] with the #348 hardening baked in as the default:
     *  - **Debug-only** Timber-piped [HttpLoggingInterceptor] (release gets no HTTP
     *    logging at all): Census headers only, Feed none, otherwise BODY, with bearer/signature headers
     *    and [redactedQueryParams] stripped so secrets never reach logs.
     *  - Standard connect/read/write timeouts.
     *
     * @param logTag Timber tag for the HTTP log lines (debug builds only).
     * @param profile Census suppresses bodies and uses a ten-second connect timeout; Feed gets no HTTP logging.
     * @param redactedQueryParams query-param names to redact from logs; defaults to the
     *   shared secret list (`api_key`). Pass a superset if the upstream carries more secrets.
     */
    fun okHttpClient(
        logTag: String,
        profile: ClientProfile = ClientProfile.Default,
        redactedQueryParams: List<String> = DEFAULT_REDACTED_QUERY_PARAMS,
    ): OkHttpClient = OkHttpClient.Builder()
        .apply {
            // Debug builds only — release gets no HTTP logging at all (#348).
            if (BuildConfig.DEBUG && profile != ClientProfile.Feed) {
                val timberLogger = HttpLoggingInterceptor.Logger { message ->
                    Timber.tag(logTag).v(message)
                }
                addInterceptor(
                    HttpLoggingInterceptor(timberLogger).apply {
                        // Census bodies contain skeletons / enrollment IDs. Never log bodies,
                        // even at VERBOSE; signatures and the bearer are secrets too.
                        level = if (profile == ClientProfile.Census) HttpLoggingInterceptor.Level.HEADERS
                            else HttpLoggingInterceptor.Level.BODY
                        redactHeader(CensusHeaders.AUTHORIZATION)
                        redactHeader(CensusHeaders.SIGNATURE)
                        redactedQueryParams.forEach { redactQueryParams(it) }
                    }
                )
            }
        }
        .connectTimeout(if (profile == ClientProfile.Census) 10L else DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    /**
     * Builds a [Retrofit] for [baseUrl] over [client], decoding JSON via [json]
     * (defaults to [govApiJson]). The kotlinx-serialization converter is wired here so
     * no provider repeats the media-type/converter boilerplate.
     */
    fun retrofit(
        baseUrl: String,
        client: OkHttpClient,
        json: Json = govApiJson,
    ): Retrofit {
        val contentType = "application/json".toMediaType()
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()
    }
}
