/*
 * Copyright (c) 2024-2025 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PriceInfoTypeData(
    @SerialName("firstYear") val firstYear: PriceInfoData,
    // 2nd and 3rd year pricing is unavailable for plugins without a continuity discount
    @SerialName("secondYear") val secondYear: PriceInfoData? = null,
    @SerialName("thirdYear") val thirdYear: PriceInfoData? = null,
)