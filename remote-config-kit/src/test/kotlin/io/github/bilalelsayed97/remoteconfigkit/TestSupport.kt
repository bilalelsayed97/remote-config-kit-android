@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.bilalelsayed97.remoteconfigkit

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.time.Duration

const val CONFIG_URL = "https://raw.githubusercontent.com/example/public-config/main/config.json"
val cachePrefix = "remote_config_kit.${fnv1a32(CONFIG_URL)}."

fun json(value: Any?): JsonElement = value.toJsonElement()

fun document(
    version: Int = 1,
    conditions: List<Map<String, Any?>> = emptyList(),
    parameters: List<Map<String, Any?>> = emptyList(),
): String = json(
    mapOf(
        "schemaVersion" to 1,
        "version" to version,
        "publishedAt" to "2026-10-02T12:00:00Z",
        "groups" to emptyList<Any>(),
        "conditions" to conditions,
        "parameters" to parameters,
    ),
).toString()

fun parameter(
    key: String,
    type: String,
    defaultValue: Any?,
    overrides: List<Map<String, Any?>> = emptyList(),
): Map<String, Any?> = mapOf(
    "key" to key,
    "type" to type,
    "default" to defaultValue,
    "description" to "Used in tests",
    "groupId" to null,
    "overrides" to overrides,
)

fun condition(id: String, rules: List<Map<String, Any?>>): Map<String, Any?> =
    mapOf("id" to id, "name" to id, "color" to "blue", "match" to "all", "rules" to rules)

fun rule(attribute: String, operator: String, value: Any?): Map<String, Any?> =
    mapOf("attribute" to attribute, "operator" to operator, "value" to value)

fun override(conditionId: String, value: Any?): Map<String, Any?> =
    mapOf("conditionId" to conditionId, "value" to value)

fun ok(body: String, headers: Map<String, String> = emptyMap()) = HttpResponse(200, headers, body.encodeToByteArray())

fun offline(): Nothing = throw IOException("offline")

/** A clock that follows the test scheduler's virtual time. */
class SchedulerClock(private val scheduler: TestCoroutineScheduler) : Clock() {
    private val base = Instant.parse("2026-11-03T12:00:00Z")

    override fun instant(): Instant = base.plusMillis(scheduler.currentTime)

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId?): Clock = this
}

suspend fun TestScope.createKit(
    client: ConfigHttpClient,
    store: InMemoryKeyValueStore,
    context: TargetingContext = TargetingContext(),
    defaults: Map<String, Any?> = emptyMap(),
    minimumFetchInterval: Duration = Duration.ZERO,
    foreground: ForegroundSource = ManualForegroundSource(),
): RemoteConfigKit = RemoteConfigKit.create(
    options = RemoteConfigOptions(
        url = CONFIG_URL,
        context = context,
        defaults = defaults,
        minimumFetchInterval = minimumFetchInterval,
    ),
    store = store,
    appMetadata = AppMetadata(appId = "com.example.app", appVersion = "2.5.74", buildNumber = "36"),
    httpClient = client,
    foregroundSource = foreground,
    clock = SchedulerClock(testScheduler),
    dispatcher = StandardTestDispatcher(testScheduler),
)

val JsonNullValue: JsonElement = JsonNull

fun jsonArrayOf(vararg values: Any?) = JsonArray(values.map { it.toJsonElement() })

fun jsonObjectOf(vararg pairs: Pair<String, Any?>) = JsonObject(pairs.associate { it.first to it.second.toJsonElement() })

fun primitive(value: String) = JsonPrimitive(value)
