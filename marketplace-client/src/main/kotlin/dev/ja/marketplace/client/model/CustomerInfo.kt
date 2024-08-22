/*
 * Copyright (c) 2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client.model

import dev.ja.marketplace.client.CustomerId
import dev.ja.marketplace.client.NullableStringSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CustomerInfo(
    @SerialName("code")
    val code: CustomerId,
    @SerialName("country")
    val country: String,
    @SerialName("type")
    val type: CustomerType,
    @SerialName("name")
    @Serializable(with = NullableStringSerializer::class)
    val name: String? = null,
) : Comparable<CustomerInfo> {
    override fun compareTo(other: CustomerInfo): Int {
        return code.compareTo(other.code)
    }
}