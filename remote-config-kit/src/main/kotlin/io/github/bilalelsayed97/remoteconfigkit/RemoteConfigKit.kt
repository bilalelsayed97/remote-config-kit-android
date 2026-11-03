package io.github.bilalelsayed97.remoteconfigkit

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectIndexed
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/** Where a value came from. */
public enum class ConfigSource {
    /** The active remote document has the parameter. */
    REMOTE,

    /** The parameter came from [RemoteConfigOptions.defaults]. */
    LOCAL_DEFAULT,

    /** Neither has it; typed getters return `false`, `""`, `0`, or `null`. */
    STATIC_FALLBACK,
}

/** The network outcome of [RemoteConfigKit.fetch]. */
public enum class FetchStatus {
    /** HTTP 200: a new document was staged. */
    FETCHED,

    /** HTTP 304: the saved document is current. */
    UNCHANGED,

    /** Skipped because of [RemoteConfigOptions.minimumFetchInterval]. */
    SKIPPED,
}

/** What [RemoteConfigKit.start] does on a first install with no saved document. */
public enum class InitialFetchPolicy {
    /** Wait until a remote document is activated, retrying connection failures. */
    WAIT_FOR_REMOTE,

    /** Return immediately; getters use local defaults while the kit fetches in the background. */
    USE_DEFAULTS,
}

/**
 * @property url A public `https://raw.githubusercontent.com/OWNER/REPO/REF/PATH` URL.
 * @property defaults Local values used before a document is active; values must be JSON-compatible.
 */
public data class RemoteConfigOptions(
    val url: String,
    val context: TargetingContext = TargetingContext(),
    val defaults: Map<String, Any?> = emptyMap(),
    val minimumFetchInterval: Duration = 1.hours,
    val fetchTimeout: Duration = 20.seconds,
)

/** The selected raw value and the exact attributes used to select it. */
public data class ConfigInspection(
    val key: String,
    /** The raw selected value; `null` when neither the document nor the defaults have the key. */
    val value: JsonElement?,
    val source: ConfigSource,
    val activeVersion: Int?,
    val matchedConditionId: String?,
    val targeting: TargetingSnapshot,
)

/**
 * Reads a JSON config from a public GitHub repository and evaluates its conditions on device.
 *
 * Getters are synchronous and thread-safe. Collect [changes] or [revision] to update UI after an
 * activation or [updateContext].
 */
public class RemoteConfigKit private constructor(
    private val options: RemoteConfigOptions,
    private val url: String,
    private val prefix: String,
    private val store: KeyValueStore,
    private val httpClient: ConfigHttpClient,
    private val appMetadata: AppMetadata,
    private val foregroundSource: ForegroundSource,
    private val clock: Clock,
    private val defaults: Map<String, JsonElement>,
    dispatcher: CoroutineDispatcher,
    /** A kit-generated random 128-bit hex ID, shared by every config URL in the app. */
    public val installationId: String,
    /** When the kit first initialized with the current store. */
    public val firstOpen: Instant,
    initial: State,
) {
    private class State(
        val active: ConfigDocument?,
        val activeRaw: String?,
        val activeEvaluationTime: Instant,
        val pending: ConfigDocument?,
        val pendingRaw: String?,
        val pendingEvaluationTime: Instant?,
        val etag: String?,
        val lastModified: String?,
        val lastFetchTime: Instant?,
        val context: TargetingContext,
    ) {
        fun copy(
            active: ConfigDocument? = this.active,
            activeRaw: String? = this.activeRaw,
            activeEvaluationTime: Instant = this.activeEvaluationTime,
            pending: ConfigDocument? = this.pending,
            pendingRaw: String? = this.pendingRaw,
            pendingEvaluationTime: Instant? = this.pendingEvaluationTime,
            etag: String? = this.etag,
            lastModified: String? = this.lastModified,
            lastFetchTime: Instant? = this.lastFetchTime,
            context: TargetingContext = this.context,
        ) = State(
            active, activeRaw, activeEvaluationTime, pending, pendingRaw, pendingEvaluationTime,
            etag, lastModified, lastFetchTime, context,
        )
    }

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val persistence = Mutex()
    private val lock = Any()
    private val changeEvents = MutableSharedFlow<Unit>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val revisionState = MutableStateFlow(0L)

    @Volatile private var state = initial

    @Volatile private var started = false

    @Volatile private var foreground = true

    @Volatile private var closed = false

    @Volatile private var refreshError: Exception? = null

    // Guarded by lock.
    private var inFlightFetch: Deferred<FetchStatus>? = null
    private var startup: Deferred<Unit>? = null
    private var backgroundRefresh: Job? = null

    /** The `version` of the active document, or `null` before the first activation. */
    public val activeVersion: Int? get() = state.active?.version

    /** The pinned `date_time` targeting value. */
    public val activeEvaluationTime: Instant get() = state.activeEvaluationTime

    /** The time of the last HTTP 200 or 304 response. */
    public val lastFetchTime: Instant? get() = state.lastFetchTime

    /** The most recent background refresh error, if any. */
    public val lastRefreshError: Exception? get() = refreshError

    /** Emits after an activation that changed the active snapshot and after [updateContext]. */
    public val changes: SharedFlow<Unit> = changeEvents.asSharedFlow()

    /** Increments with every [changes] event; convenient for Compose `collectAsState`. */
    public val revision: StateFlow<Long> = revisionState.asStateFlow()

    // Startup and automatic refresh

    /**
     * Activates a saved pending document and starts foreground refreshes.
     *
     * With cached data it returns promptly and checks GitHub in the background. On a first install,
     * [InitialFetchPolicy.WAIT_FOR_REMOTE] keeps retrying connection failures, timeouts, and HTTP
     * 403/408/429/5xx with backoff from 1 to 30 seconds; other HTTP errors and invalid documents throw.
     */
    public suspend fun start(initialFetchPolicy: InitialFetchPolicy = InitialFetchPolicy.WAIT_FOR_REMOTE) {
        ensureOpen()
        if (started) return
        val job = synchronized(lock) {
            startup ?: scope.async { runStart(initialFetchPolicy) }.also { startup = it }
        }
        awaitOrClosed(job)
    }

    private suspend fun runStart(policy: InitialFetchPolicy) {
        try {
            val hadCachedConfig = state.active != null || state.pending != null
            if (state.pending != null) activate()
            if (state.active == null && policy == InitialFetchPolicy.WAIT_FOR_REMOTE) waitForFirstConfig()
            ensureOpen()
            started = true
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                foregroundSource.isForeground().collectIndexed { index, isForeground ->
                    foreground = isForeground
                    if (index > 0 && isForeground) requestBackgroundRefresh()
                }
            }
            val interval = if (options.minimumFetchInterval == Duration.ZERO) 60.seconds else options.minimumFetchInterval
            scope.launch {
                while (isActive) {
                    delay(interval)
                    requestBackgroundRefresh()
                }
            }
            if (hadCachedConfig || state.active == null) requestBackgroundRefresh()
        } finally {
            if (!started) synchronized(lock) { startup = null }
        }
    }

    private suspend fun waitForFirstConfig() {
        var retryDelay = 1.seconds
        while (true) {
            ensureOpen()
            try {
                fetchAndActivate()
                if (state.active != null) return
                throw IllegalStateException("No remote configuration was activated")
            } catch (error: ConfigFetchHttpException) {
                ensureOpen()
                if (!error.isRetryable) throw error
            } catch (error: IOException) {
                // A connection failure or timeout can recover without restarting the app.
                ensureOpen()
            }
            delay(retryDelay)
            retryDelay = minOf(retryDelay * 2, 30.seconds)
        }
    }

    private fun requestBackgroundRefresh() {
        synchronized(lock) {
            if (closed || !foreground || backgroundRefresh?.isActive == true) return
            backgroundRefresh = scope.launch { refreshSafely() }
        }
    }

    private suspend fun refreshSafely() {
        try {
            if (state.active == null) waitForFirstConfig() else fetchAndActivate()
            refreshError = null
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (!closed) refreshError = error
        }
    }

    // Targeting

    /** Replaces the app-supplied targeting attributes; values are recalculated immediately. */
    public fun updateContext(context: TargetingContext) {
        validate(context)
        updateState { it.copy(context = context.withAppMetadata(appMetadata)) }
        emitChange()
    }

    /** The attributes the evaluator uses right now. */
    public fun targetingSnapshot(): TargetingSnapshot = snapshotOf(state)

    /** The raw selected value, its source, and the targeting snapshot used to select it. */
    public fun inspect(key: String): ConfigInspection {
        val current = state
        val snapshot = snapshotOf(current)
        val selected = resolve(current, key, snapshot)
        return ConfigInspection(
            key = key,
            value = selected.value,
            source = selected.source,
            activeVersion = current.active?.version,
            matchedConditionId = selected.matchedConditionId,
            targeting = snapshot,
        )
    }

    // Fetch and activate

    /** Downloads and stages a valid document. Errors leave the active configuration intact. */
    public suspend fun fetch(): FetchStatus {
        ensureOpen()
        val job = synchronized(lock) {
            inFlightFetch?.takeIf { it.isActive } ?: scope.async { fetchOnce() }.also { inFlightFetch = it }
        }
        return awaitOrClosed(job)
    }

    private suspend fun fetchOnce(): FetchStatus {
        val current = state
        val lastFetch = current.lastFetchTime
        if ((current.active != null || current.pending != null) && lastFetch != null &&
            java.time.Duration.between(lastFetch, clock.instant()).toMillis() < options.minimumFetchInterval.inWholeMilliseconds
        ) {
            return FetchStatus.SKIPPED
        }
        val headers = buildMap {
            if (current.etag != null) {
                put("If-None-Match", current.etag)
            } else if (current.lastModified != null) {
                put("If-Modified-Since", current.lastModified)
            }
        }
        val response = withTimeoutOrNull(options.fetchTimeout) {
            httpClient.get(url, headers, options.fetchTimeout)
        } ?: throw ConfigFetchTimeoutException("Config fetch timed out after ${options.fetchTimeout}")
        ensureOpen()
        if (response.statusCode == 304) {
            if (state.active == null && state.pending == null) {
                throw ConfigFormatException("304 received without cached configuration")
            }
            recordFetchTime(now())
            return FetchStatus.UNCHANGED
        }
        if (response.statusCode != 200) throw ConfigFetchHttpException(response.statusCode, url)
        val body = decodeUtf8(response.body)
        val parsed = ConfigDocument.parse(body)
        val evaluatedAt = now()
        val responseEtag = response.headers["etag"]
        val responseLastModified = response.headers["last-modified"]
        persistence.withLock {
            store.putString(prefix + "pending", body)
            store.putString(prefix + "pending_evaluated_at", formatInstant(evaluatedAt))
            if (responseEtag == null) store.remove(prefix + "etag") else store.putString(prefix + "etag", responseEtag)
            if (responseLastModified == null) {
                store.remove(prefix + "last_modified")
            } else {
                store.putString(prefix + "last_modified", responseLastModified)
            }
            store.putString(prefix + "fetched_at", formatInstant(evaluatedAt))
            updateState {
                it.copy(
                    pending = parsed,
                    pendingRaw = body,
                    pendingEvaluationTime = evaluatedAt,
                    etag = responseEtag,
                    lastModified = responseLastModified,
                    lastFetchTime = evaluatedAt,
                )
            }
        }
        return FetchStatus.FETCHED
    }

    /** Makes the last fetched document visible to getters, returning whether the active snapshot changed. */
    public suspend fun activate(): Boolean {
        ensureOpen()
        val changed = persistence.withLock {
            val current = state
            val pending = current.pending ?: return@withLock false
            val pendingRaw = current.pendingRaw ?: return@withLock false
            val evaluatedAt = current.pendingEvaluationTime ?: now()
            val changed = pendingRaw != current.activeRaw || evaluatedAt != current.activeEvaluationTime
            store.putString(prefix + "active", pendingRaw)
            store.putString(prefix + "active_evaluated_at", formatInstant(evaluatedAt))
            store.remove(prefix + "pending")
            store.remove(prefix + "pending_evaluated_at")
            updateState {
                it.copy(
                    active = pending,
                    activeRaw = pendingRaw,
                    activeEvaluationTime = evaluatedAt,
                    pending = null,
                    pendingRaw = null,
                    pendingEvaluationTime = null,
                )
            }
            changed
        }
        if (changed) emitChange()
        return changed
    }

    /** Calls [fetch] and then [activate]. */
    public suspend fun fetchAndActivate(): Boolean {
        fetch()
        return activate()
    }

    // Typed values

    public fun getSource(key: String): ConfigSource = resolve(state, key).source

    /** The value as a Boolean, or `false` when the key is missing. */
    public fun getBool(key: String): Boolean {
        val resolved = resolve(state, key)
        val value = resolved.value ?: return false
        return value.booleanValue ?: throw IllegalStateException("$key is not a boolean")
    }

    /** The value as a String, or `""` when the key is missing. */
    public fun getString(key: String): String {
        val resolved = resolve(state, key)
        val value = resolved.value ?: return ""
        return value.stringValue ?: throw IllegalStateException("$key is not a string")
    }

    /** The value as an Int, or `0` when the key is missing. Throws for fractional or out-of-range numbers. */
    public fun getInt(key: String): Int {
        val number = getLong(key)
        if (number < Int.MIN_VALUE || number > Int.MAX_VALUE) throw IllegalStateException("$key is not an integer")
        return number.toInt()
    }

    /** The value as a Long, or `0` when the key is missing. Throws for fractional numbers. */
    public fun getLong(key: String): Long {
        val resolved = resolve(state, key)
        val value = resolved.value ?: return 0
        val number = value.numberValue
        if (number == null || !number.isFinite() || number != Math.rint(number) || number >= 9.223372036854776E18 ||
            number < -9.223372036854776E18
        ) {
            throw IllegalStateException("$key is not an integer")
        }
        return number.toLong()
    }

    /** The value as a Double, or `0.0` when the key is missing. */
    public fun getDouble(key: String): Double {
        val resolved = resolve(state, key)
        val value = resolved.value ?: return 0.0
        return value.numberValue ?: throw IllegalStateException("$key is not a number")
    }

    /** The raw immutable JSON value, or `null` when the key is missing. */
    public fun getJson(key: String): JsonElement? = resolve(state, key).value

    // Closing

    /** Stops refreshes and pending startup retries. Later calls that need the network throw. */
    public fun close() {
        if (closed) return
        closed = true
        scope.cancel()
    }

    // Private

    private suspend fun <T> awaitOrClosed(job: Deferred<T>): T = try {
        job.await()
    } catch (error: CancellationException) {
        ensureOpen()
        throw error
    }

    private fun ensureOpen() {
        check(!closed) { CLOSED_MESSAGE }
    }

    private fun emitChange() {
        if (closed) return
        changeEvents.tryEmit(Unit)
        revisionState.update { it + 1 }
    }

    private inline fun updateState(transform: (State) -> State) {
        synchronized(lock) { state = transform(state) }
    }

    private suspend fun recordFetchTime(time: Instant) {
        persistence.withLock {
            store.putString(prefix + "fetched_at", formatInstant(time))
            updateState { it.copy(lastFetchTime = time) }
        }
    }

    private fun now(): Instant = clock.instant().truncatedTo(ChronoUnit.MILLIS)

    private fun snapshotOf(current: State): TargetingSnapshot =
        TargetingSnapshot.capture(current.context, installationId, firstOpen, current.activeEvaluationTime)

    private class Resolved(val value: JsonElement?, val source: ConfigSource, val matchedConditionId: String?)

    private fun resolve(current: State, key: String, snapshot: TargetingSnapshot? = null): Resolved {
        val active = current.active
        val parameter = active?.parameters?.get(key)
        if (parameter != null) {
            val evaluator = TargetingEvaluator(snapshot ?: snapshotOf(current))
            for (condition in active.conditions) {
                val override = parameter.overrides.firstOrNull { it.conditionId == condition.id }
                if (override != null && evaluator.matches(condition)) {
                    return Resolved(override.value, ConfigSource.REMOTE, condition.id)
                }
            }
            return Resolved(parameter.defaultValue, ConfigSource.REMOTE, null)
        }
        defaults[key]?.let { return Resolved(it, ConfigSource.LOCAL_DEFAULT, null) }
        return Resolved(null, ConfigSource.STATIC_FALLBACK, null)
    }

    public companion object {
        private const val CLOSED_MESSAGE = "RemoteConfigKit is closed"
        private const val INSTALLATION_KEY = "remote_config_kit.installation_id"
        private const val FIRST_OPEN_KEY = "remote_config_kit.first_open"

        /**
         * Loads saved state from [SharedPreferences][android.content.SharedPreferences] without
         * fetching, reading app ID, version, and build from the installed package.
         */
        public suspend fun create(
            context: Context,
            options: RemoteConfigOptions,
            httpClient: ConfigHttpClient = UrlConnectionHttpClient,
        ): RemoteConfigKit = create(
            options = options,
            store = SharedPreferencesStore(context),
            appMetadata = AppMetadata.from(context),
            httpClient = httpClient,
            foregroundSource = ProcessLifecycleForegroundSource,
        )

        /** Loads saved state from [store] without fetching. Every collaborator is replaceable. */
        public suspend fun create(
            options: RemoteConfigOptions,
            store: KeyValueStore,
            appMetadata: AppMetadata,
            httpClient: ConfigHttpClient = UrlConnectionHttpClient,
            foregroundSource: ForegroundSource = ManualForegroundSource(),
            clock: Clock = Clock.systemUTC(),
            dispatcher: CoroutineDispatcher = Dispatchers.Default,
        ): RemoteConfigKit {
            val url = validatedUrl(options.url)
            require(!options.minimumFetchInterval.isNegative() && options.fetchTimeout.isPositive()) {
                "Fetch intervals must be nonnegative and timeout positive"
            }
            validate(options.context)
            val defaults = options.defaults.mapValues { it.value.toJsonElement() }
            val now = clock.instant().truncatedTo(ChronoUnit.MILLIS)
            val prefix = "remote_config_kit.${fnv1a32(url)}."
            val installationId = installationId(store)
            val firstOpen = store.getString(FIRST_OPEN_KEY)?.let(::parseInstant)
                ?: now.also { store.putString(FIRST_OPEN_KEY, formatInstant(it)) }
            val activeRaw = validatedCache(store, prefix + "active")
            val savedPending = store.getString(prefix + "pending")
            val pendingRaw = validatedCache(store, prefix + "pending")
            var etag = store.getString(prefix + "etag")
            var lastModified = store.getString(prefix + "last_modified")
            var fetchTime = store.getString(prefix + "fetched_at")?.let(::parseInstant)
            val activeEvaluationTime = store.getString(prefix + "active_evaluated_at")?.let(::parseInstant)
                ?: if (activeRaw == null) now else fetchTime ?: now
            val pendingEvaluationTime = pendingRaw?.let {
                store.getString(prefix + "pending_evaluated_at")?.let(::parseInstant) ?: fetchTime ?: now
            }
            if ((savedPending != null && pendingRaw == null) || (activeRaw == null && pendingRaw == null)) {
                for (key in listOf("etag", "last_modified", "fetched_at", "pending_evaluated_at")) {
                    store.remove(prefix + key)
                }
                etag = null
                lastModified = null
                fetchTime = null
            }
            if (activeRaw == null && pendingRaw == null) store.remove(prefix + "active_evaluated_at")

            return RemoteConfigKit(
                options = options,
                url = url,
                prefix = prefix,
                store = store,
                httpClient = httpClient,
                appMetadata = appMetadata,
                foregroundSource = foregroundSource,
                clock = clock,
                defaults = defaults,
                dispatcher = dispatcher,
                installationId = installationId,
                firstOpen = firstOpen,
                initial = State(
                    active = activeRaw?.let(ConfigDocument::parse),
                    activeRaw = activeRaw,
                    activeEvaluationTime = activeEvaluationTime,
                    pending = pendingRaw?.let(ConfigDocument::parse),
                    pendingRaw = pendingRaw,
                    pendingEvaluationTime = pendingEvaluationTime,
                    etag = etag,
                    lastModified = lastModified,
                    lastFetchTime = fetchTime,
                    context = options.context.withAppMetadata(appMetadata),
                ),
            )
        }

        private fun validatedUrl(text: String): String {
            val uri = try {
                URI(text)
            } catch (error: URISyntaxException) {
                throw IllegalArgumentException("Use a public raw GitHub HTTPS URL: $text", error)
            }
            val segments = uri.rawPath.orEmpty().split('/').drop(1)
            require(
                uri.scheme == "https" &&
                    uri.host == "raw.githubusercontent.com" &&
                    segments.size >= 4 &&
                    uri.rawUserInfo == null &&
                    uri.rawFragment == null,
            ) { "Use a public raw GitHub HTTPS URL: $text" }
            return uri.toString()
        }

        private fun validate(context: TargetingContext) {
            context.userProperties.values.forEach { it.toJsonElement() }
            context.customSignals.values.forEach { it.toJsonElement() }
        }

        private suspend fun installationId(store: KeyValueStore): String {
            store.getString(INSTALLATION_KEY)?.takeIf { it.isNotEmpty() }?.let { return it }
            val bytes = ByteArray(16).also(SecureRandom()::nextBytes)
            val identifier = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
            store.putString(INSTALLATION_KEY, identifier)
            return identifier
        }

        private suspend fun validatedCache(store: KeyValueStore, key: String): String? {
            val cached = store.getString(key) ?: return null
            return try {
                ConfigDocument.parse(cached)
                cached
            } catch (error: ConfigFormatException) {
                store.remove(key)
                null
            }
        }

        private fun decodeUtf8(bytes: ByteArray): String = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (error: CharacterCodingException) {
            throw ConfigFormatException("Body is not UTF-8")
        }
    }
}
