/*
 * Copyright (c) 2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client.model

import dev.ja.marketplace.client.LicenseId
import dev.ja.marketplace.client.YearMonthDayRange
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class JsonPluginSaleItem(
    @SerialName("type")
    val type: PluginSaleItemType,
    @SerialName("licenseIds")
    val licenseIds: List<LicenseId>,
    @SerialName("subscriptionDates")
    val subscriptionDates: YearMonthDayRange,
    @SerialName("amount")
    val amount: Double,
    @SerialName("amountUsd")
    val amountUSD: Double,
    @SerialName("discountDescriptions")
    val discountDescriptions: List<PluginSaleItemDiscount>
)