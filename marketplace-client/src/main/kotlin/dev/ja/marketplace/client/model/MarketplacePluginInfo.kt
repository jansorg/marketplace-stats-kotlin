/*
 * Copyright (c) 2024-2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client.model

import dev.ja.marketplace.client.MonetaryAmountUsdSerializer
import dev.ja.marketplace.client.YearMonthDateSerializer
import dev.ja.marketplace.client.YearMonthDay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import javax.money.MonetaryAmount

@Serializable
data class MarketplacePluginInfo(
    @SerialName("code")
    val code: String,
    @SerialName("name")
    val name: String,
    @SerialName("periods")
    val licensePeriods: List<LicensePeriod>,
    @SerialName("individualPrice")
    @Serializable(with = MonetaryAmountUsdSerializer::class)
    val individualPrice: MonetaryAmount? = null,
    @SerialName("businessPrice")
    @Serializable(with = MonetaryAmountUsdSerializer::class)
    val businessPrice: MonetaryAmount? = null,
    @SerialName("licensing")
    val licensingType: LicensingType? = null,
    @SerialName("status")
    val status: String? = null,
    @SerialName("allowResellers")
    val allowResellers: Boolean? = null,
    @SerialName("link")
    val pluginPageLink: String? = null,
    @SerialName("trialPeriod")
    val trialPeriod: Int? = null,
    @SerialName("hasContinuityDiscount")
    val hasContinuityDiscount: Boolean? = null,
    // only available with fullInfo=true
    @SerialName("versions")
    val majorVersions: List<PluginMajorVersion>? = null,
    /** The date, at which the continuity discount was disabled, if it was disabled. */
    @SerialName("continuityDiscountDisabledAt")
    @Serializable(YearMonthDateSerializer::class)
    val continuityDiscountDisabledAt: YearMonthDay? = null,
) {
    /**
     * The license periods, which customers can buy, or `null` if they're unknown.
     *
     * The Marketplace currently offers either subscriptions (annual and monthly) or perpetual licenses,
     * and the API returns no periods for perpetual licenses.
     * The periods returned by the API are always included, so that plugins offering both kinds of licenses are supported.
     */
    val offeredLicensePeriods: Set<LicensePeriod>?
        get() {
            val periods = licensePeriods.toMutableSet()
            when (licensingType) {
                LicensingType.Perpetual -> periods += LicensePeriod.Perpetual
                LicensingType.Subscription, LicensingType.SubscriptionWithFallback -> {
                    if (periods.isEmpty()) {
                        periods += LicensePeriod.Annual
                        periods += LicensePeriod.Monthly
                    }
                }

                null -> {}
            }
            return periods.takeIf { it.isNotEmpty() }
        }
}