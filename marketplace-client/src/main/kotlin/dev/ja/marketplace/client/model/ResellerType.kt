/*
 * Copyright (c) 2024-2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client.model

import dev.ja.marketplace.client.TolerantEnumSerializer
import kotlinx.serialization.Serializable

/**
 * The type of a reseller. Types, which the client doesn't know, are [Unknown].
 */
@Serializable(ResellerTypeSerializer::class)
enum class ResellerType(val apiValue: String, val displayString: String) {
    Reseller("Reseller", "Reseller"),
    Organization("Organization", "Organization"),

    /** A type, which the Marketplace added later. */
    Unknown("Unknown", "Unknown"),
}

object ResellerTypeSerializer : TolerantEnumSerializer<ResellerType>(
    "ResellerType", ResellerType.entries, ResellerType.Unknown, ResellerType::apiValue,
)
