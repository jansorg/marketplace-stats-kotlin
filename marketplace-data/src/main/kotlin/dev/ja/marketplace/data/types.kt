/*
 * Copyright (c) 2023-2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data

import dev.ja.marketplace.client.CustomerId
import dev.ja.marketplace.client.LicenseId
import dev.ja.marketplace.client.LicensePeriod
import dev.ja.marketplace.client.PluginId
import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.util.isZero
import java.math.BigDecimal
import java.math.RoundingMode
import javax.money.MonetaryAmount

data class PercentageValue(val value: BigDecimal) {
    companion object {
        private val ONE_HUNDRED_DECIMAL = BigDecimal.valueOf(100)

        val ONE_HUNDRED = PercentageValue(ONE_HUNDRED_DECIMAL)

        // used by render.kte
        @Suppress("MemberVisibilityCanBePrivate")
        val ZERO = PercentageValue(BigDecimal(0.0))

        fun of(first: MonetaryAmount, second: MonetaryAmount): PercentageValue {
            assert(first.currency == second.currency)

            if (first.isZero || second.isZero) {
                return ZERO
            }

            val firstValue = first.number.numberValue(BigDecimal::class.java)
            val secondValue = second.number.numberValue(BigDecimal::class.java)
            return PercentageValue(firstValue.divide(secondValue, 10, RoundingMode.HALF_UP) * ONE_HUNDRED_DECIMAL)
        }

        fun of(first: BigDecimal, second: BigDecimal): PercentageValue {
            if (first.isZero() || second.isZero()) {
                return ZERO
            }
            return PercentageValue(first.divide(second, 10, RoundingMode.HALF_UP) * BigDecimal(100.0))
        }

        fun of(first: Int, second: Int): PercentageValue {
            return of(BigDecimal(first), BigDecimal(second))
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

data class LinkedLicense(val id: LicenseId, val pluginId: PluginId)

data class LinkedChurnRate(
    val churnRate: Any,
    val previousPeriodMarkerDate: YearMonthDay,
    val currentPeriodMarkerDate: YearMonthDay,
    val period: LicensePeriod,
    val pluginId: PluginId,
)

const val NoValue: String = "—"