/*
 * Copyright (c) 2024-2025 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PriceInfoByPeriod(
    @SerialName("annual")
    val annual: PriceInfoTypeData? = null,
    @SerialName("monthly")
    val monthly: PriceInfoTypeData? = null,
    @SerialName("perpetual")
    val perpetual: PriceInfoData? = null,
)