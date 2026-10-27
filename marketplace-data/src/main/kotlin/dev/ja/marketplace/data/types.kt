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

/**
 * @return The value as [IncompleteValue], if [isIncomplete], e.g. the sales of the current month or of the first month
 *  of a plugin, which started within the month. [NoValue] and incomplete values are returned as they are.
 */
fun incompleteIf(value: Any, isIncomplete: Boolean): Any = when {
    !isIncomplete || value is IncompleteValue || value == NoValue -> value
    else -> IncompleteValue(value)
}

/**
 * @return If the sums of a period, which starts at [periodStart], only cover a part of it, because the plugin started
 *  within the period at [pluginStart], see [PluginData.getFirstActivityDate].
 */
fun isPartialPeriod(periodStart: YearMonthDay, pluginStart: YearMonthDay?): Boolean = pluginStart != null && pluginStart > periodStart

/** @return The tooltip of the first period of a plugin, which started within the period. */
fun partialPeriodTooltip(pluginStart: YearMonthDay): String = "Incomplete: the first data of the plugin is of ${pluginStart.asIsoString}"

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
    /** The license period, `null` for the combined churn of all periods. */
    val period: LicensePeriod?,
    val pluginId: PluginId,
    /** Number of licenses or customers, which were active in the previous period and churned. */
    val churnedCount: Int = 0,
    /** Number of licenses or customers, which were active in the previous period. */
    val previousActiveCount: Int = 0,
) {
    /** The segment of the license period in the link to the churned licenses, `all` for the combined churn. */
    val periodLinkSegment: String
        get() = period?.linkSegmentName ?: ALL_PERIODS_LINK_SEGMENT

    companion object {
        const val ALL_PERIODS_LINK_SEGMENT = "all"
    }
}

const val NoValue: String = "—"

/** @return The country or [NoValue] for a customer without a country. */
fun String.orEmptyCountry(): String = ifEmpty { NoValue }

/** Tooltip of downloads, which may still change. */
const val IncompleteDownloadsTooltip: String = "The downloads of a day are reported on the next day at the earliest"

/** @return The label of a month, e.g. `2026-03`, which charts recognize as month. */
fun monthLabel(year: Int, month: Int): String = String.format("%04d-%02d", year, month)
