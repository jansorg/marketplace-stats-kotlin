/*
 * Copyright (c) 2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client

typealias PluginTagName = String

/**
 * A set of common marketplace tags.
 */
object MarketplaceTag {
    val ExternallyPaid = "Externally-Paid"
    val LanguagePack: PluginTagName = "Language Pack"
    val Localization: PluginTagName = "Localization"
    val Theme: PluginTagName = "theme"
}