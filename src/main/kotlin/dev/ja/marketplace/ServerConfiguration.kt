/*
 * Copyright (c) 2024-2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace

data class ServerConfiguration(
    val userDisplayCurrencyCode: String,
    val showResellerCharges: Boolean,
    /** Shows the sales and downloads of all paid plugins on the list of plugins. Their data is loaded at the start. */
    val showAllPluginsSummary: Boolean = true,
)
