/*
 * Copyright (c) 2023-2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data

import dev.ja.marketplace.client.CustomerId
import dev.ja.marketplace.client.LicenseId
import dev.ja.marketplace.client.PluginId
import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.client.model.LicensePeriod
import dev.ja.marketplace.data.format.Formatters
import dev.ja.marketplace.util.isZero
import java.math.BigDecimal
import java.math.RoundingMode
import javax.money.MonetaryAmount

data class PercentageValue(val value: BigDecimal) {
    override fun toString(): String {
        return Formatters.formatPercentage(value)
    }

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

/**
 * A value, which may still change, e.g. a conversion rate of trials which can still convert.
 */
data class IncompleteValue(val value: Any)

const val IncompleteRecurringRevenueTooltip: String =
    "Incomplete: licenses renewed later in this period are not included yet"

data class LinkedCustomer(val id: CustomerId, val pluginId: PluginId)

data class LinkedLicense(val id: LicenseId, val pluginId: PluginId)

data class LinkedRefNum(val refNum: String, val pluginId: PluginId)

/**
 * The end of a purchase of an expired license, rendered with a symbol, so that expired licenses aren't only marked by
 * their color.
 * @param expiryDate The end of the latest purchase of the license.
 */
data class ExpiredLicenseEnd(val date: YearMonthDay, val expiryDate: YearMonthDay)

data class LinkedChurnRate(
    val churnRate: Any,
    /**
     * The churn rate as a number, e.g. for charts.
     */
    val rate: PercentageValue,
    val previousPeriodMarkerDate: YearMonthDay,
    val currentPeriodMarkerDate: YearMonthDay,
    val period: LicensePeriod,
    val pluginId: PluginId,
    /** Number of licenses or customers, which were active in the previous period and churned. */
    val churnedCount: Int = 0,
    /** Number of licenses or customers, which were active in the previous period. */
    val previousActiveCount: Int = 0,
)

const val NoValue: String = "—"

/** Tooltip of downloads, which may still change. */
const val IncompleteDownloadsTooltip: String = "The downloads of a day are reported on the next day at the earliest"