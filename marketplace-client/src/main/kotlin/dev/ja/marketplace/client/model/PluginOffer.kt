/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client.model

import dev.ja.marketplace.client.PluginId
import dev.ja.marketplace.client.YearMonthDateSerializer
import dev.ja.marketplace.client.YearMonthDay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

typealias PluginOfferId = Int

/**
 * A special offer of a paid plugin, which customers redeem with coupons, see [PluginOfferCoupon].
 * An offer either has a discount or a free period.
 */
@Serializable
data class PluginOffer(
    @SerialName("id")
    val id: PluginOfferId,
    @SerialName("name")
    val name: String,
    @SerialName("pluginId")
    val pluginId: PluginId,
    @SerialName("startDate")
    @Serializable(YearMonthDateSerializer::class)
    val startDate: YearMonthDay,
    /** The billing periods of the licenses, which the offer applies to. Unknown periods are dropped. */
    @SerialName("billingPeriod")
    val billingPeriods: List<String> = emptyList(),
    /** Discount in percent, if the offer has a discount. */
    @SerialName("percent")
    val discountPercent: Int? = null,
    /** Number of free months, if the offer has a free period. */
    @SerialName("freePeriodInMonths")
    val freePeriodInMonths: Int? = null,
) {
    /** The billing periods as [LicensePeriod], values unknown to this client are ignored. */
    val licensePeriods: List<LicensePeriod>
        get() = billingPeriods.mapNotNull { period -> LicensePeriod.entries.firstOrNull { it.name == period } }
}
