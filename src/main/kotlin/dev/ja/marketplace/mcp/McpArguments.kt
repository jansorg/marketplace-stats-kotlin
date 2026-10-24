/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.mcp

import dev.ja.marketplace.client.model.PluginInfoSummary
import dev.ja.marketplace.services.JetBrainsProductCode
import kotlinx.serialization.json.*

// Helpers to read the arguments of the MCP tools and to write common values of the results.

internal fun JsonObjectBuilder.putPlugin(plugin: PluginInfoSummary) {
    put("pluginId", plugin.id)
    put("pluginName", plugin.name)
}

internal fun JsonObjectBuilder.putProduct(product: JetBrainsProductCode) {
    put("productCode", product.code)
}

internal fun productArgument(arguments: JsonObject): JetBrainsProductCode {
    val code = arguments.stringArgument("productCode")?.trim()?.uppercase()?.takeIf(String::isNotEmpty)
    return code?.let(JetBrainsProductCode::byProductCode) ?: JetBrainsProductCode.IntelliJIDEAUltimate
}

internal fun McpDataProvider.findPlugin(arguments: JsonObject): PluginInfoSummary {
    val value = arguments["pluginId"] as? JsonPrimitive
        ?: throw IllegalArgumentException("pluginId is required, use list_plugins to find the IDs of the plugins")
    val pluginId = value.intOrNull ?: value.contentOrNull?.trim()?.toIntOrNull()
        ?: throw IllegalArgumentException("pluginId must be a number, but was '${value.content}'")
    return plugins().firstOrNull { it.id == pluginId }
        ?: throw IllegalArgumentException("Unknown plugin ID $pluginId, use list_plugins to find the IDs of the plugins")
}

internal fun JsonObject.intArgument(name: String): Int? {
    val value = this[name] ?: return null
    return when (value) {
        is JsonNull -> null
        is JsonPrimitive -> value.intOrNull ?: value.content.trim().takeIf(String::isNotEmpty)?.let {
            it.toIntOrNull() ?: throw IllegalArgumentException("$name must be a number, but was '${value.content}'")
        }

        else -> throw IllegalArgumentException("$name must be a number")
    }
}

internal fun JsonObject.stringListArgument(name: String): List<String>? {
    val value = this[name] ?: return null
    return when (value) {
        is JsonNull -> null
        is JsonArray -> value.map { (it as? JsonPrimitive)?.content ?: throw IllegalArgumentException("$name must be an array of strings") }
        // a single value or a comma-separated list
        is JsonPrimitive -> value.content.split(',').map(String::trim).filter(String::isNotEmpty)
        else -> throw IllegalArgumentException("$name must be an array of strings")
    }
}

internal fun JsonObject.stringArgument(name: String): String? {
    val value = this[name] ?: return null
    return when (value) {
        is JsonNull -> null
        is JsonPrimitive -> value.content
        else -> throw IllegalArgumentException("$name must be a string")
    }
}

internal fun JsonObject.booleanArgument(name: String): Boolean? {
    val value = this[name] ?: return null
    return when (value) {
        is JsonNull -> null
        is JsonPrimitive -> value.booleanOrNull ?: value.content.trim().takeIf(String::isNotEmpty)?.let {
            it.toBooleanStrictOrNull() ?: throw IllegalArgumentException("$name must be true or false, but was '${value.content}'")
        }

        else -> throw IllegalArgumentException("$name must be true or false")
    }
}

/**
 * The name of an enum value in arguments and results of the tools, in lower camel case, e.g. `individual` or `annual`.
 */
internal val Enum<*>.toolName: String
    get() = name.replaceFirstChar(Char::lowercaseChar)

internal inline fun <reified T : Enum<T>> JsonObject.enumArgument(name: String): T? {
    val value = stringArgument(name)?.trim()?.takeIf(String::isNotEmpty) ?: return null
    return enumValues<T>().firstOrNull { it.name.equals(value, ignoreCase = true) }
        ?: throw IllegalArgumentException("$name must be one of ${enumValues<T>().joinToString { it.toolName }}, but was '$value'")
}

/**
 * @return One of the values, compared case-insensitive, or `null` if the argument is missing.
 */
internal fun JsonObject.choiceArgument(name: String, values: List<String>): String? {
    val value = stringArgument(name)?.trim()?.takeIf(String::isNotEmpty) ?: return null
    return values.firstOrNull { it.equals(value, ignoreCase = true) }
        ?: throw IllegalArgumentException("$name must be one of ${values.joinToString()}, but was '$value'")
}
