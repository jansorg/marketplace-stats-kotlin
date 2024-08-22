/*
 * Copyright (c) 2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client.model

import dev.ja.marketplace.client.PluginProductCode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PluginPurchaseInfo(
    @SerialName("productCode")
    val productCode: PluginProductCode,
    @SerialName("buyUrl")
    val buyUrl: String? = null,
    @SerialName("purchaseTerms")
    val purchaseTerms: String? = null,
    @SerialName("optional")
    val optional: Boolean,
    @SerialName("trialPeriod")
    val trialPeriod: Int? = null,
)