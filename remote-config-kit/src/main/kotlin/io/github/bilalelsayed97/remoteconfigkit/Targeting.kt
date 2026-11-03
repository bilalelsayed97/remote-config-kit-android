package io.github.bilalelsayed97.remoteconfigkit

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.util.Locale

/**
 * App-supplied attributes used to evaluate published conditions on-device.
 *
 * [appId], [appVersion], and [buildNumber] default to the installed package's values;
 * [platform] defaults to `android` and [locale] to [Locale.getDefault]. Values in
 * [userProperties] and [customSignals] must be JSON-compatible (see [toJsonElement]).
 */
public data class TargetingContext(
    val appId: String? = null,
    val platform: String? = null,
    val appVersion: String? = null,
    val buildNumber: String? = null,
    val locale: String? = null,
    val country: String? = null,
    val userId: String? = null,
    val audienceIds: Set<String> = emptySet(),
    val userProperties: Map<String, Any?> = emptyMap(),
    val customSignals: Map<String, Any?> = emptyMap(),
) {
    internal fun withAppMetadata(metadata: AppMetadata): TargetingContext = copy(
        appId = appId ?: metadata.appId,
        appVersion = appVersion ?: metadata.appVersion,
        buildNumber = buildNumber ?: metadata.buildNumber,
    )
}

/** Immutable values used for one condition evaluation. */
public class TargetingSnapshot internal constructor(
    /** Every attribute the evaluator reads; app-supplied values that are missing are [JsonNull]. */
    public val attributes: Map<String, JsonElement>,
) {
    /** The attributes as one JSON object, for a private debug view. */
    public fun toJson(): JsonObject = JsonObject(attributes)

    /** The stable rollout bucket in `0 until 100` for a condition, as used by `percentage` rules. */
    public fun percentageFor(conditionId: String, seed: String? = null): Double {
        require(seed == null || seed.isNotEmpty()) { "seed must be nonempty" }
        val installationId = attributes["installation_id"]?.stringValue.orEmpty()
        return fnv1a32("${seed ?: conditionId}:$installationId").toDouble() / 4_294_967_296.0 * 100
    }

    override fun equals(other: Any?): Boolean = other is TargetingSnapshot && other.attributes == attributes

    override fun hashCode(): Int = attributes.hashCode()

    override fun toString(): String = "TargetingSnapshot(${toJson()})"

    internal companion object {
        fun capture(
            context: TargetingContext,
            installationId: String,
            firstOpen: Instant,
            evaluatedAt: Instant,
        ): TargetingSnapshot {
            val attributes = linkedMapOf<String, JsonElement>(
                "app_id" to JsonPrimitive(context.appId),
                "platform" to JsonPrimitive(context.platform ?: "android"),
                "app_version" to JsonPrimitive(context.appVersion),
                "build_number" to JsonPrimitive(context.buildNumber),
                "locale" to JsonPrimitive(context.locale ?: Locale.getDefault().toString()),
                "country" to JsonPrimitive(context.country),
                "user_id" to JsonPrimitive(context.userId),
                "user_exists" to JsonPrimitive(true),
                "audience" to JsonArray(context.audienceIds.sorted().map(::JsonPrimitive)),
                "installation_id" to JsonPrimitive(installationId),
                "first_open" to JsonPrimitive(formatInstant(firstOpen)),
                "date_time" to JsonPrimitive(formatInstant(evaluatedAt)),
            )
            for ((key, value) in context.userProperties) {
                attributes["user_property.$key"] = value.toJsonElement()
            }
            for ((key, value) in context.customSignals) {
                attributes["custom_signal.$key"] = value.toJsonElement()
            }
            return TargetingSnapshot(attributes.toMap())
        }
    }
}

internal class TargetingEvaluator(private val snapshot: TargetingSnapshot) {
    fun matches(condition: ConfigCondition): Boolean =
        if (condition.matchAny) {
            condition.rules.any { matchesRule(condition.id, it) }
        } else {
            condition.rules.all { matchesRule(condition.id, it) }
        }

    fun matchesRule(conditionId: String, rule: ConfigRule): Boolean {
        if (rule.attribute == "percentage") {
            return snapshot.percentageFor(conditionId, rule.seed) < (rule.value.numberValue ?: 0.0)
        }
        val actual = snapshot.attributes[rule.attribute]?.takeUnless { it.isJsonNull } ?: return false
        val expected = rule.value
        val attribute = rule.attribute
        return when (rule.operator) {
            "eq" -> equals(attribute, actual, expected)
            "neq" -> !equals(attribute, actual, expected)
            "in" -> (expected as JsonArray).any { equals(attribute, actual, it) }
            "not_in" -> (expected as JsonArray).none { equals(attribute, actual, it) }
            "contains" -> contains(actual, expected) ?: false
            "not_contains" -> contains(actual, expected)?.not() ?: false
            "lt" -> ordering(attribute, actual, expected)?.let { it < 0 } ?: false
            "lte" -> ordering(attribute, actual, expected)?.let { it <= 0 } ?: false
            "gt" -> ordering(attribute, actual, expected)?.let { it > 0 } ?: false
            "gte" -> ordering(attribute, actual, expected)?.let { it >= 0 } ?: false
            "between" -> {
                val endpoints = expected as JsonArray
                val lower = ordering(attribute, actual, endpoints[0])
                val upper = ordering(attribute, actual, endpoints[1])
                lower != null && upper != null && lower >= 0 && upper <= 0
            }
            else -> false
        }
    }
}

private fun equals(attribute: String, actual: JsonElement, expected: JsonElement): Boolean {
    if (attribute == "audience" && actual is JsonArray) return actual.any { jsonEquals(it, expected) }
    return jsonEquals(actual, expected)
}

/** `null` when the actual value supports neither substring nor element checks. */
private fun contains(actual: JsonElement, expected: JsonElement): Boolean? {
    val text = actual.stringValue
    val part = expected.stringValue
    if (text != null && part != null) return text.contains(part)
    if (actual is JsonArray) return actual.any { jsonEquals(it, expected) }
    return null
}

private fun ordering(attribute: String, actual: JsonElement, expected: JsonElement): Int? {
    if (attribute == "app_version") return compareVersions(actual.comparisonText, expected.comparisonText)
    if (attribute == "date_time" || attribute == "first_open") {
        val left = parseInstant(actual.comparisonText) ?: return null
        val right = parseInstant(expected.comparisonText) ?: return null
        return left.compareTo(right)
    }
    val left = actual.numberValue ?: actual.comparisonText.toDoubleOrNull() ?: return null
    val right = expected.numberValue ?: expected.comparisonText.toDoubleOrNull() ?: return null
    return left.compareTo(right)
}

private val versionPattern = Regex("""^\d+(\.\d+)*$""")

private fun compareVersions(leftText: String, rightText: String): Int? {
    if (!versionPattern.matches(leftText) || !versionPattern.matches(rightText)) return null
    val left = leftText.split('.').map { it.toLongOrNull() ?: return null }
    val right = rightText.split('.').map { it.toLongOrNull() ?: return null }
    for (index in 0 until maxOf(left.size, right.size)) {
        val order = left.getOrElse(index) { 0 }.compareTo(right.getOrElse(index) { 0 })
        if (order != 0) return order
    }
    return 0
}

/** 32-bit FNV-1a over the UTF-8 bytes of [source], as an unsigned value. */
public fun fnv1a32(source: String): Long {
    var hash = 0x811c9dc5.toInt()
    for (byte in source.encodeToByteArray()) {
        hash = (hash xor (byte.toInt() and 0xff)) * 0x01000193
    }
    return hash.toLong() and 0xffffffffL
}
