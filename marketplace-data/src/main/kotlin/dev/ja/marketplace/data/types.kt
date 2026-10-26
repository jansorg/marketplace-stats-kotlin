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
            require(first.currency == second.currency) { "Amounts of different currencies: ${first.currency} and ${second.currency}" }

            if (first.isZero || second.isZero) {
                return ZERO
            }

            val firstValue = first.number.numberValue(BigDecimal::class.java)
            val secondValue = second.number.numberValue(BigDecimal::class.java)
            return PercentageValue(firstValue.divide(secondValue, 10, RoundingMode.HALF_UP) * ONE_HUNDRED_DECIMAL)
        }

        /**
         * @return The share of [part] of [total] for rates, e.g. a retention, `null` if [total] is zero and there's
         *  nothing to compare. A share of zero is 0 %, unlike [ZERO] of [of], which is shown as "no value".
         */
        fun rateOf(part: Int, total: Int): PercentageValue? = rateOf(BigDecimal(part), BigDecimal(total))

        /** @see rateOf */
        fun rateOf(part: BigDecimal, total: BigDecimal): PercentageValue? {
            return if (total.signum() == 0) null else PercentageValue(part.divide(total, 10, RoundingMode.HALF_UP) * ONE_HUNDRED_DECIMAL)
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
    "Incomplete: the recurring revenue of today, it changes until the end of this period"

data class LinkedCustomer(val id: CustomerId, val pluginId: PluginId)

/** The name of a customer, linked to the page of the customer. */
data class LinkedCustomerName(val name: String, val id: CustomerId, val pluginId: PluginId)

data class LinkedLicense(val id: LicenseId, val pluginId: PluginId)

data class LinkedRefNum(val refNum: String, val pluginId: PluginId)

/**
 * The end of a purchase of an expired license, rendered with a symbol, so that expired licenses aren't only marked by
 * their color.
 * @param expiryDate The end of the latest purchase of the license.
 */
data class ExpiredLicenseEnd(val date: YearMonthDay, val expiryDate: YearMonthDay)

/**
 * The end of the latest purchase of a license by a customer, when another customer renewed the license,
 * e.g. after it was transferred to an organization. It's rendered with a link to the other customer.
 * @param licenseEnd The end of the latest purchase of the license.
 */
data class TransferredLicenseEnd(
    val date: YearMonthDay,
    val customer: LinkedCustomer,
    val customerName: String?,
    val licenseEnd: YearMonthDay,
)

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

/** @return The country or [NoValue] for a customer without a country. */
fun String.orEmptyCountry(): String = ifEmpty { NoValue }

/** Tooltip of downloads, which may still change. */
const val IncompleteDownloadsTooltip: String = "The downloads of a day are reported on the next day at the earliest"