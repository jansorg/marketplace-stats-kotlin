/*
 * Copyright (c) 2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.trackers

import dev.ja.marketplace.client.*
import dev.ja.marketplace.data.ContinuityDiscount
import dev.ja.marketplace.data.LicenseId
import dev.ja.marketplace.data.LicenseInfo
import dev.ja.marketplace.data.PluginPricing
import dev.ja.marketplace.exchangeRate.ExchangeRates
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.*

/**
 * Tracks recurring revenue for a given time period, e.g. one month or one year.
 */
abstract class RecurringRevenueTracker(
    private val dateRange: YearMonthDayRange,
    private val continuityTracker: ContinuityDiscountTracker,
    private val pluginPricing: PluginPricing,
    private val exchangeRates: ExchangeRates,
) {
    private val latestSales = TreeMap<LicenseId, LicenseInfo>()

    protected abstract fun nextContinuityCheckDate(date: YearMonthDay): YearMonthDay

    protected abstract fun basePriceFactor(licensePeriod: LicensePeriod): BigDecimal

    fun processLicenseSale(licenseInfo: LicenseInfo) {
        if (!licenseInfo.isPaidLicense) {
            return
        }

        // only keep the latest valid sale of a license
        if (isValid(licenseInfo)) {
            latestSales.merge(licenseInfo.id, licenseInfo) { old, new ->
                when {
                    new.validity > old.validity -> new
                    else -> old
                }
            }
        }
    }

    fun getResult(): RecurringRevenue {
        val resultAmounts = AmountWithCurrencyTracker(exchangeRates)

        for ((_, license) in latestSales) {
            val basePrice = pluginPricing.getBasePrice(
                dateRange.end,
                license.sale.customer,
                license.sale.licensePeriod,
                nextContinuityDiscount(license)
            )
            assert(basePrice != null) {
                "Unable to find base price for country ${license.sale.customer.country}"
            }

            val basePriceFactor = basePriceFactor(license.sale.licensePeriod)
            val otherDiscountsFactor = otherDiscountsFactor(license)
            val factors = basePriceFactor * otherDiscountsFactor.toBigDecimal()

            resultAmounts += Marketplace.paidAmount(license.validity.end, basePrice!! * factors)
        }

        return RecurringRevenue(dateRange, resultAmounts)
    }

    /**
     * @return The expected continuity discount for the next month or year.
     */
    private fun nextContinuityDiscount(license: LicenseInfo): ContinuityDiscount {
        return continuityTracker.nextContinuity(license.id, nextContinuityCheckDate(license.validity.start))
    }

    private fun isValid(license: LicenseInfo): Boolean {
        // Only take licenses which are valid at the end of the date range, e.g. at the end of a month.
        // Licenses only valid at the start but expiring in the middle of a month do not contribute to recurring revenue.
        return dateRange.end in license.validity
    }

    private fun otherDiscountsFactor(licenseInfo: LicenseInfo): Double {
        val otherPercent = licenseInfo.saleLineItem.discountDescriptions
            .filterNot(PluginSaleItemDiscount::isContinuityDiscount)
            .mapNotNull { it.percent }

        var factor = 1.0
        for (percent in otherPercent) {
            factor *= 1.0 - percent / 100.0
        }
        return factor
    }
}

class MonthlyRecurringRevenueTracker(
    timeRange: YearMonthDayRange,
    continuityTracker: ContinuityDiscountTracker,
    pluginPricing: PluginPricing,
    exchangeRates: ExchangeRates,
) : RecurringRevenueTracker(timeRange, continuityTracker, pluginPricing, exchangeRates) {
    private val annualToMonthly = BigDecimal.ONE.divide(BigDecimal.valueOf(12), 10, RoundingMode.HALF_UP)

    override fun nextContinuityCheckDate(date: YearMonthDay): YearMonthDay {
        return date.add(0, 1, 0)
    }

    override fun basePriceFactor(licensePeriod: LicensePeriod): BigDecimal {
        return when (licensePeriod) {
            LicensePeriod.Monthly -> BigDecimal.ONE
            LicensePeriod.Annual -> annualToMonthly
        }
    }
}

class AnnualRecurringRevenueTracker(
    timeRange: YearMonthDayRange,
    continuityTracker: ContinuityDiscountTracker,
    pluginPricing: PluginPricing,
    exchangeRates: ExchangeRates,
) : RecurringRevenueTracker(timeRange, continuityTracker, pluginPricing, exchangeRates) {
    private val monthlyToAnnual = BigDecimal.valueOf(12)

    override fun basePriceFactor(licensePeriod: LicensePeriod): BigDecimal {
        return when (licensePeriod) {
            LicensePeriod.Monthly -> monthlyToAnnual
            LicensePeriod.Annual -> BigDecimal.ONE
        }
    }

    override fun nextContinuityCheckDate(date: YearMonthDay): YearMonthDay {
        return date.add(1, 0, 0)
    }
}

data class RecurringRevenue(
    val dateRange: YearMonthDayRange,
    val amounts: AmountWithCurrencyTracker
) {
    fun renderTooltip(): String {
        return buildString {
            for (value in amounts.getValues()) {
                append(value.amount.setScale(2, RoundingMode.HALF_UP))
                append(" ")
                append(value.currencyCode)
                append("\n")
            }
        }
    }
}