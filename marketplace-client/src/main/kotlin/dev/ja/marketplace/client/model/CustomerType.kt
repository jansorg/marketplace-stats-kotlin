/*
 * Copyright (c) 2024-2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client.model

import dev.ja.marketplace.client.TolerantEnumSerializer
import kotlinx.serialization.Serializable

/**
 * The type of a customer. Types, which the client doesn't know, are [Unknown].
 */
// keep order because it's used for sorting
@Serializable(CustomerTypeSerializer::class)
enum class CustomerType(val apiValue: String) {
    Organization("Organization"),
    Individual("Personal"),

    /** A type, which the Marketplace added later. */
    Unknown("Unknown"),
}

object CustomerTypeSerializer : TolerantEnumSerializer<CustomerType>(
    "CustomerType", CustomerType.entries, CustomerType.Unknown, CustomerType::apiValue,
)
