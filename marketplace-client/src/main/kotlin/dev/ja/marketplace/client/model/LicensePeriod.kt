/*
 * Copyright (c) 2024-2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client.model

import dev.ja.marketplace.client.TolerantEnumSerializer
import kotlinx.serialization.Serializable

/**
 * The billing period of a license. Periods, which the client doesn't know, are [Unknown].
 */
// the order of the enum values is used for sorting
@Serializable(LicensePeriodSerializer::class)
enum class LicensePeriod(val apiValue: String, val linkSegmentName: String) {
    Annual("Annual", "annual"),
    Monthly("Monthly", "monthly"),
    Perpetual("Perpetual", "perpetual"),

    /** A period, which the Marketplace added later. */
    Unknown("Unknown", "unknown"),
}

object LicensePeriodSerializer : TolerantEnumSerializer<LicensePeriod>(
    "LicensePeriod", LicensePeriod.entries, LicensePeriod.Unknown, LicensePeriod::apiValue,
)
