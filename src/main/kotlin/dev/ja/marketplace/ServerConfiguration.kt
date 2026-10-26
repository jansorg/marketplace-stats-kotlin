/*
 * Copyright (c) 2024-2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace

import dev.ja.marketplace.client.PluginId

data class ServerConfiguration(
    val userDisplayCurrencyCode: String,
    val showResellerCharges: Boolean,
    /** Shows the sales and downloads of all paid plugins on the list of plugins. Their data is loaded at the start. */
    val showAllPluginsSummary: Boolean = true,
    /** The custom events of the charts, `null` without a file of events. */
    val customEvents: CustomEventsFile? = null,
) {
    /**
     * @param plugin The plugin of a page, `null` for the summary of all plugins.
     * @return The custom events of the plugin, shown as vertical lines of the charts.
     */
    fun customEvents(plugin: PluginId?): List<CustomEvent> {
        return customEvents?.events()?.filter { it.appliesTo(plugin) } ?: emptyList()
    }
}
