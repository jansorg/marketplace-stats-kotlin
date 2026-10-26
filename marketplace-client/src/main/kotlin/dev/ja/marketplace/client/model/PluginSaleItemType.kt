/*
 * Copyright (c) 2024-2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client.model

import dev.ja.marketplace.client.TolerantEnumSerializer
import kotlinx.serialization.Serializable

/**
 * The type of a line item of a sale. Types, which the client doesn't know, are [Unknown].
 */
@Serializable(PluginSaleItemTypeSerializer::class)
enum class PluginSaleItemType(val apiValue: String) {
    New("NEW"),
    Renew("RENEW"),

    /** A type, which the Marketplace added later. */
    Unknown("UNKNOWN"),
}

object PluginSaleItemTypeSerializer : TolerantEnumSerializer<PluginSaleItemType>(
    "PluginSaleItemType", PluginSaleItemType.entries, PluginSaleItemType.Unknown, PluginSaleItemType::apiValue,
)
