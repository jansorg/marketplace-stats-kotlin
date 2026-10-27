/*
 * Copyright (c) 2024-2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client.model

import dev.ja.marketplace.client.TolerantEnumSerializer
import kotlinx.serialization.Serializable

/**
 * The licensing type of a paid plugin. Types, which the client doesn't know, are [Unknown].
 */
@Serializable(LicensingTypeSerializer::class)
enum class LicensingType(val apiValue: String) {
    Subscription("SUBSCRIPTION"),
    SubscriptionWithFallback("SUBSCRIPTION_FALLBACK"),
    Perpetual("PERPETUAL_UNBOUNDED"),

    /** A type, which the Marketplace added later. */
    Unknown("UNKNOWN"),
}

object LicensingTypeSerializer : TolerantEnumSerializer<LicensingType>(
    "LicensingType", LicensingType.entries, LicensingType.Unknown, LicensingType::apiValue,
)
