/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Finds the properties of API responses, which the models of the client don't have.
 *
 * The client decodes the responses with `ignoreUnknownKeys`, so that new properties of the API don't break it.
 * New properties would go unnoticed, this finds them, e.g. in `LiveApiCoverageTest`.
 */
object UnknownJsonKeys {
    /**
     * @param json A response of the API.
     * @param descriptor The descriptor of the serializer of the model, e.g. `PluginInfo.serializer().descriptor`.
     * @return The paths of the keys, which aren't properties of the model, e.g. `lineItems[].discountDescriptions[].code`.
     *  Keys of nested objects are only checked, if their parent is a property of the model.
     */
    fun find(json: JsonElement, descriptor: SerialDescriptor): Set<String> {
        return sortedSetOf<String>().also { find(json, descriptor, "", it) }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun find(json: JsonElement, descriptor: SerialDescriptor, path: String, result: MutableSet<String>) {
        when (descriptor.kind) {
            StructureKind.CLASS, StructureKind.OBJECT -> {
                // value classes are serialized as their value
                if (descriptor.isInline) {
                    find(json, descriptor.getElementDescriptor(0), path, result)
                    return
                }

                val jsonObject = json as? JsonObject ?: return
                for ((key, value) in jsonObject) {
                    val keyPath = if (path.isEmpty()) key else "$path.$key"
                    when (val index = descriptor.getElementIndex(key)) {
                        CompositeDecoder.UNKNOWN_NAME -> result += keyPath
                        else -> find(value, descriptor.getElementDescriptor(index), keyPath, result)
                    }
                }
            }

            StructureKind.LIST -> {
                val jsonArray = json as? JsonArray ?: return
                jsonArray.forEach { find(it, descriptor.getElementDescriptor(0), "$path[]", result) }
            }

            StructureKind.MAP -> {
                val jsonObject = json as? JsonObject ?: return
                jsonObject.values.forEach { find(it, descriptor.getElementDescriptor(1), "$path.*", result) }
            }

            // primitives, enums, polymorphic and contextual values have no properties, which could be checked
            else -> Unit
        }
    }
}
