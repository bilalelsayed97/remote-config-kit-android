package io.github.bilalelsayed97.remoteconfigkit

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal class ConfigDocument(
    val version: Int,
    val conditions: List<ConfigCondition>,
    val parameters: Map<String, ConfigParameter>,
) {
    companion object {
        fun parse(source: String): ConfigDocument {
            val json = try {
                Json.parseToJsonElement(source)
            } catch (error: SerializationException) {
                throw ConfigFormatException("Malformed JSON")
            }
            val root = json.objectOf("config")
            if (root["schemaVersion"]?.let { it is JsonPrimitive && !it.isString && it.content == "1" } != true) {
                throw ConfigFormatException("Unsupported config schemaVersion")
            }
            val version = root["version"].integer("version")
            if (version < 0) throw ConfigFormatException("version must be nonnegative")
            if (parseInstant(root["publishedAt"].string("publishedAt")) == null) {
                throw ConfigFormatException("publishedAt must be an ISO 8601 date")
            }

            val groupIds = mutableSetOf<String>()
            for (entry in root["groups"].listOf("groups")) {
                val group = entry.objectOf("group")
                val id = group["id"].string("group.id")
                group["name"].string("group.name")
                group["description"].text("group.description")
                if (!groupIds.add(id)) throw ConfigFormatException("Duplicate group: $id")
            }

            val conditions = root["conditions"].listOf("conditions")
                .map { ConfigCondition.parse(it.objectOf("condition")) }
            val conditionIds = mutableSetOf<String>()
            for (condition in conditions) {
                if (!conditionIds.add(condition.id)) {
                    throw ConfigFormatException("Duplicate condition: ${condition.id}")
                }
            }

            val parameters = linkedMapOf<String, ConfigParameter>()
            for (entry in root["parameters"].listOf("parameters")) {
                val parameter = ConfigParameter.parse(entry.objectOf("parameter"))
                if (parameter.key in parameters) {
                    throw ConfigFormatException("Duplicate parameter: ${parameter.key}")
                }
                if (parameter.groupId != null && parameter.groupId !in groupIds) {
                    throw ConfigFormatException("Unknown group: ${parameter.groupId}")
                }
                for (override in parameter.overrides) {
                    if (override.conditionId !in conditionIds) {
                        throw ConfigFormatException("Unknown condition: ${override.conditionId}")
                    }
                }
                parameters[parameter.key] = parameter
            }
            return ConfigDocument(version, conditions, parameters)
        }
    }
}

internal class ConfigCondition(val id: String, val matchAny: Boolean, val rules: List<ConfigRule>) {
    companion object {
        fun parse(source: JsonObject): ConfigCondition {
            val id = source["id"].string("condition.id")
            source["name"].string("condition.name")
            source["color"].string("condition.color")
            val match = source["match"].string("condition.match")
            if (match != "all" && match != "any") throw ConfigFormatException("Invalid condition.match: $match")
            val rules = source["rules"].listOf("condition.rules").map { ConfigRule.parse(it.objectOf("rule")) }
            if (rules.isEmpty()) throw ConfigFormatException("Condition $id has no rules")
            return ConfigCondition(id, match == "any", rules)
        }
    }
}

internal class ConfigRule(
    val attribute: String,
    val operator: String,
    val value: JsonElement,
    val seed: String?,
) {
    companion object {
        private val operators = setOf(
            "eq", "neq", "in", "not_in", "lt", "lte", "gt", "gte", "contains", "not_contains", "between",
        )
        private val attributes = setOf(
            "app_id", "platform", "app_version", "build_number", "locale", "country", "user_id",
            "user_exists", "installation_id", "first_open", "date_time", "audience", "percentage",
        )

        fun parse(source: JsonObject): ConfigRule {
            val attribute = source["attribute"].string("rule.attribute")
            val operator = source["operator"].string("rule.operator")
            if (!isValidAttribute(attribute)) throw ConfigFormatException("Unsupported rule attribute: $attribute")
            if (operator !in operators) throw ConfigFormatException("Unsupported rule operator: $operator")
            val value = source["value"] ?: throw ConfigFormatException("rule.value is required")
            var seed: String? = null
            if ("seed" in source) {
                val text = source.getValue("seed").stringValue
                if (attribute != "percentage" || text.isNullOrEmpty()) {
                    throw ConfigFormatException("seed must be a nonempty string on a percentage rule")
                }
                seed = text
            }
            if (attribute == "percentage") {
                val threshold = value.numberValue
                if (operator != "lt" || threshold == null || threshold < 0 || threshold > 100) {
                    throw ConfigFormatException("percentage requires lt and a value 0–100")
                }
            }
            if ((operator == "in" || operator == "not_in") && value !is JsonArray) {
                throw ConfigFormatException("$operator requires a list")
            }
            if (operator == "between" && (value !is JsonArray || value.size != 2)) {
                throw ConfigFormatException("between requires two endpoints")
            }
            return ConfigRule(attribute, operator, value, seed)
        }

        private fun isValidAttribute(attribute: String): Boolean =
            attribute in attributes ||
                (attribute.startsWith("user_property.") && attribute.length > 14) ||
                (attribute.startsWith("custom_signal.") && attribute.length > 14)
    }
}

internal class ConfigParameter(
    val key: String,
    val type: String,
    val defaultValue: JsonElement,
    val groupId: String?,
    val overrides: List<ConfigOverride>,
) {
    companion object {
        fun parse(source: JsonObject): ConfigParameter {
            val key = source["key"].string("parameter.key")
            val type = source["type"].string("parameter.type")
            val defaultValue = source["default"] ?: throw ConfigFormatException("Parameter $key has no default")
            checkValue(type, defaultValue, "$key.default")
            source["description"].text("$key.description")
            val rawGroupId = source["groupId"]
            val groupId = when {
                rawGroupId == null || rawGroupId.isJsonNull -> null
                rawGroupId.stringValue != null -> rawGroupId.stringValue
                else -> throw ConfigFormatException("$key.groupId must be a string or null")
            }
            val overrides = source["overrides"].listOf("$key.overrides")
                .map { ConfigOverride.parse(it.objectOf("override"), type) }
            val seen = mutableSetOf<String>()
            for (override in overrides) {
                if (!seen.add(override.conditionId)) throw ConfigFormatException("Duplicate override for $key")
            }
            return ConfigParameter(key, type, defaultValue, groupId, overrides)
        }
    }
}

internal class ConfigOverride(val conditionId: String, val value: JsonElement) {
    companion object {
        fun parse(source: JsonObject, type: String): ConfigOverride {
            val conditionId = source["conditionId"].string("override.conditionId")
            val value = source["value"] ?: throw ConfigFormatException("override.value is required")
            checkValue(type, value, "override.value")
            return ConfigOverride(conditionId, value)
        }
    }
}

private fun JsonElement?.objectOf(label: String): JsonObject =
    this as? JsonObject ?: throw ConfigFormatException("$label must be an object")

private fun JsonElement?.listOf(label: String): JsonArray =
    this as? JsonArray ?: throw ConfigFormatException("$label must be a list")

private fun JsonElement?.string(label: String): String =
    this?.stringValue?.takeIf { it.isNotEmpty() } ?: throw ConfigFormatException("$label must be a nonempty string")

private fun JsonElement?.text(label: String): String =
    this?.stringValue ?: throw ConfigFormatException("$label must be a string")

private fun JsonElement?.integer(label: String): Int =
    (this as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toIntOrNull()
        ?: throw ConfigFormatException("$label must be an integer")

private fun checkValue(type: String, value: JsonElement, label: String) {
    val valid = when (type) {
        "boolean" -> value.booleanValue != null
        "string" -> value.stringValue != null
        "number" -> value.numberValue != null
        "json" -> true
        else -> throw ConfigFormatException("Unknown parameter type: $type")
    }
    if (!valid) throw ConfigFormatException("$label does not match $type")
}
