package io.github.bilalelsayed97.remoteconfigkit

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Converts app-supplied values (null, Boolean, Number, String, Iterable, Array, Map with String
 * keys, or a [JsonElement]) to an immutable [JsonElement].
 *
 * @throws IllegalArgumentException for other values, non-finite numbers, or non-String map keys.
 */
public fun Any?.toJsonElement(): JsonElement = when (this) {
    null -> JsonNull
    is JsonElement -> this
    is Boolean -> JsonPrimitive(this)
    is String -> JsonPrimitive(this)
    is Number -> {
        require(toDouble().isFinite()) { "Use finite numbers: $this" }
        JsonPrimitive(this)
    }
    is Iterable<*> -> JsonArray(map { it.toJsonElement() })
    is Array<*> -> JsonArray(map { it.toJsonElement() })
    is Map<*, *> -> JsonObject(
        entries.associate { (key, value) ->
            require(key is String) { "Signal keys must be strings" }
            key to value.toJsonElement()
        },
    )
    else -> throw IllegalArgumentException("Use JSON-compatible values: $this")
}

internal val JsonElement.isJsonNull: Boolean get() = this is JsonNull

internal val JsonElement.booleanValue: Boolean?
    get() = (this as? JsonPrimitive)
        ?.takeIf { !it.isString && it !is JsonNull }
        ?.content
        ?.toBooleanStrictOrNull()

internal val JsonElement.numberValue: Double?
    get() = (this as? JsonPrimitive)
        ?.takeIf { !it.isString && it !is JsonNull && it.content != "true" && it.content != "false" }
        ?.content
        ?.toDoubleOrNull()

internal val JsonElement.stringValue: String?
    get() = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

/** The text that Dart's `toString()` gives a decoded JSON value; ordering rules parse it. */
internal val JsonElement.comparisonText: String
    get() = when (this) {
        is JsonPrimitive -> content
        else -> toString()
    }

/** Structural JSON equality in which `36` and `36.0` are equal. */
internal fun jsonEquals(left: JsonElement, right: JsonElement): Boolean {
    val leftNumber = left.numberValue
    val rightNumber = right.numberValue
    if (leftNumber != null || rightNumber != null) return leftNumber == rightNumber
    return when {
        left is JsonArray && right is JsonArray ->
            left.size == right.size && left.indices.all { jsonEquals(left[it], right[it]) }
        left is JsonObject && right is JsonObject ->
            left.keys == right.keys && left.keys.all { jsonEquals(left.getValue(it), right.getValue(it)) }
        left is JsonPrimitive && right is JsonPrimitive ->
            left.isString == right.isString && left.content == right.content
        else -> false
    }
}
