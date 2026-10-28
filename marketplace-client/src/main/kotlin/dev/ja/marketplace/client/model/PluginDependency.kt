/*
 * Copyright (c) 2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PluginDependency(
    @SerialName("name")
    val dependencyName: String,
    @SerialName("plugin")
    val plugin: PluginInfoShort? = null,
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