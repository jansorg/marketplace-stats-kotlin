/*
 * Copyright (c) 2024-2025 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client.model

import dev.ja.marketplace.client.LicenseId
import dev.ja.marketplace.client.YearMonthDayRange
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The JSON data as returned by the Marketplace API.
 */
@Serializable
internal data class JsonPluginSaleItem(
    @SerialName("type")
    val type: PluginSaleItemType,
    @SerialName("licenseIds")
    val licenseIds: List<LicenseId>,
    // unavailable for perpetual licenses
    @SerialName("subscriptionDates")
    val subscriptionDates: YearMonthDayRange?,
    @SerialName("amount")
    val amount: Double,
    @SerialName("amountUsd")
    val amountUSD: Double,
    @SerialName("discountDescriptions")
    val discountDescriptions: List<PluginSaleItemDiscount>
)