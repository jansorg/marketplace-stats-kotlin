/*
 * Copyright (c) 2023 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data

import dev.ja.marketplace.client.CustomerId
import dev.ja.marketplace.client.LicensePeriod
import dev.ja.marketplace.client.PluginId
import dev.ja.marketplace.client.YearMonthDay
import java.math.BigDecimal
import java.math.RoundingMode

data class PercentageValue(val value: BigDecimal) {
    companion object {
        val ONE_HUNDRED = PercentageValue(BigDecimal(100.0))

        fun of(first: BigDecimal, second: BigDecimal): PercentageValue {
            return PercentageValue(first.divide(second, 10, RoundingMode.HALF_UP) * BigDecimal(100.0))
        }
    }
}

fun Double.asPercentageValue(multiply: Boolean = true): PercentageValue? {
    return when {
        this.isNaN() -> null
        multiply -> PercentageValue(BigDecimal.valueOf(this * 100.0))
        else -> PercentageValue(BigDecimal.valueOf(this))
    }
}

fun BigDecimal.sortValue(): Long {
    return setScale(2, RoundingMode.HALF_UP).unscaledValue().toLong()
}

data class LinkedCustomer(val id: CustomerId, val pluginId: PluginId)

data class LinkedChurnRate(
    val churnRate: Any,
    val previousPeriodMarkerDate: YearMonthDay,
    val currentPeriodMarkerDate: YearMonthDay,
    val period: LicensePeriod,
    val pluginId: PluginId,
)