/*
 * Copyright (c) 2024-2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client.model

import dev.ja.marketplace.client.TolerantEnumSerializer
import kotlinx.serialization.Serializable

/**
 * The pricing model of a plugin. Models, which the client doesn't know, are [Unknown].
 */
@Serializable(PluginPricingModelSerializer::class)
enum class PluginPricingModel(val searchQueryValue: String) {
    Paid("PAID"),
    Freemium("FREEMIUM"),
    Free("FREE"),

    /** A model, which the Marketplace added later. */
    Unknown("UNKNOWN"),
}

object PluginPricingModelSerializer : TolerantEnumSerializer<PluginPricingModel>(
    "PluginPricingModel", PluginPricingModel.entries, PluginPricingModel.Unknown, PluginPricingModel::searchQueryValue,
)
