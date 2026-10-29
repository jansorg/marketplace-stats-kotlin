/*
 * Copyright (c) 2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client.model

import dev.ja.marketplace.client.PluginId
import dev.ja.marketplace.client.PluginIdOrInfoSerializer
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class PluginDependency(
    @SerialName("name")
    val dependencyName: String,
    /**
     * The ID of the plugin, which provides the dependency, `null` for a module of the platform, which isn't a plugin.
     * The API returns the plugin with its name and link, only its ID is kept, see [PluginIdOrInfoSerializer].
     */
    @SerialName("pluginId")
    @JsonNames("plugin")
    @Serializable(PluginIdOrInfoSerializer::class)
    val pluginId: PluginId? = null,
) {
    /** If the dependency is optional, the API adds [OPTIONAL_PREFIX] to its name. */
    val isOptional: Boolean get() = dependencyName.startsWith(OPTIONAL_PREFIX)

    /** The name without the prefix of an optional dependency, e.g. the ID of a plugin or a module. */
    val name: String get() = dependencyName.removePrefix(OPTIONAL_PREFIX)

    companion object {
        /** The prefix of the names of the optional dependencies, e.g. `(optional) Docker`. */
        const val OPTIONAL_PREFIX = "(optional) "
    }
}