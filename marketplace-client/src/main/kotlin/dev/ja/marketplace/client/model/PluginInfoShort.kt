/*
 * Copyright (c) 2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client.model

import dev.ja.marketplace.client.PluginId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PluginInfoShort(
    /**
     * Unique ID of the plugin.
     */
    @SerialName("id")
    override val id: PluginId,
    /**
     * Display name of the plugin.
     */
    @SerialName("name")
    override val name: String,
    /**
     * Absolute URL path to the plugin page on the Marketplace.
     */
    @SerialName("link")
    override val link: String? = null,
) : PluginInfoBase