/*
 * Copyright (c) 2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client.model

import dev.ja.marketplace.client.BigDecimalSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.math.BigDecimal

@Serializable
data class PriceInfoData(
    @SerialName("price")
    @Serializable(BigDecimalSerializer::class)
    val price: BigDecimal,
    @SerialName("priceTaxed")
    @Serializable(BigDecimalSerializer::class)
    val priceTaxed: BigDecimal? = null,
    @SerialName("newShopCode")
    val newShopCode: String,
)