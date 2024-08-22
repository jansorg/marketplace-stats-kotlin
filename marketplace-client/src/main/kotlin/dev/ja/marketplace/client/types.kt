/*
 * Copyright (c) 2023-2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client

typealias UserId = String
typealias PluginId = Int
typealias PluginXmlId = String
typealias PluginProductCode = String
typealias PluginUrl = String
typealias PluginChannel = String
typealias PluginReleaseId = Int

typealias PluginReviewId = Long

typealias CustomerId = Int
typealias ResellerId = Int
typealias LicenseId = String

typealias JetBrainsProductId = String
typealias PluginModuleName = String

typealias TrialId = String

object PluginChannelNames {
    const val Stable = ""
}

/**
 * API providing data about paid plugins.
 * This requires an API key.
 */
@Target(AnnotationTarget.FUNCTION)
annotation class PaidPluginAPI
