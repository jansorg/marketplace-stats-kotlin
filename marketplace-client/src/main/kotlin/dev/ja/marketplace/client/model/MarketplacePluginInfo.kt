/*
 * Copyright (c) 2024-2025 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client.model

import dev.ja.marketplace.client.MonetaryAmountUsdSerializer
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
)