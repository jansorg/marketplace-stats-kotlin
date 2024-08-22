/*
 * Copyright (c) 2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client.model

import dev.ja.marketplace.client.TrialId
import dev.ja.marketplace.client.YearMonthDay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PluginTrial(
    @SerialName("ref")
    val referenceId: TrialId,
    @SerialName("date")
    val date: YearMonthDay,
    @SerialName("customer")
    val customer: CustomerInfo,
) : Comparable<PluginTrial> {
    override fun compareTo(other: PluginTrial): Int {
        return date.compareTo(other.date)
    }
}