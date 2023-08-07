/*
 * Copyright (c) 2023 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace

import dev.ja.marketplace.client.PluginId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ApplicationConfig(
    @SerialName("pluginId")
    val pluginId: PluginId? = null,
    @SerialName("pluginIds")
    val pluginIds: List<PluginId> = emptyList(),
    @SerialName("apiKey")
    val marketplaceApiKey: String
)