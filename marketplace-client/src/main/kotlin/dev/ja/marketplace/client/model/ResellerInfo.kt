/*
 * Copyright (c) 2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client.model

import dev.ja.marketplace.client.ResellerId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ResellerInfo(
    @SerialName("code")
    val code: ResellerId,
    @SerialName("name")
    val name: String,
    @SerialName("country")
    val country: String,
    @SerialName("type")
    val type: ResellerType,
) {
    val tooltip: String
        get() {
            return "$name ($country, type: $type, ID: $code)"
        }
}