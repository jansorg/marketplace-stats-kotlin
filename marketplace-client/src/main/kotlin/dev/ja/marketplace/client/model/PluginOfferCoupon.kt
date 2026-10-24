/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A coupon of a [PluginOffer].
 */
@Serializable
data class PluginOfferCoupon(
    @SerialName("code")
    val code: String,
    @SerialName("active")
    val isActive: Boolean,
    /** If the coupon was marked as sent to a customer. */
    @SerialName("sent")
    val isSent: Boolean,
)
