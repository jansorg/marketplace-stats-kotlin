/*
 * Copyright (c) 2024-2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.trackers

import dev.ja.marketplace.client.*
import dev.ja.marketplace.client.model.LicensePeriod
import dev.ja.marketplace.exchangeRate.ExchangeRates
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Tracks recurring revenue for a given time period, e.g. one month or one year.
 *
 * The recurring revenue is the sum of the amounts billed for the paid subscription licenses,
 * which are valid at the end of the time period, normalized to the length of the period.
 * The billed amounts already include all discounts, e.g. continuity or promotional discounts.
 */
abstract class RecurringRevenueTracker(
    private val dateRange: YearMonthDayRange,
    private val exchangeRates: ExchangeRates,
) {
    private val latestSales = Object2ObjectOpenHashMap<LicenseId, LicenseInfo>()

    protected abstract fun periodFactor(licensePeriod: LicensePeriod): BigDecimal

    fun processLicenseSale(licenseInfo: LicenseInfo) {
        // only keep the latest valid sale of a license
        if (licenseInfo.isPaidLicense && licenseInfo.isSubscriptionLicense && isValid(licenseInfo)) {
            latestSales.merge(licenseInfo.id, licenseInfo, RecurringRevenueSales::preferred)
        }
    }

    fun getResult(): RecurringRevenue {
        val resultAmounts = MonetaryAmountTracker(exchangeRates)

        for ((_, license) in latestSales) {
            val factor = periodFactor(license.sale.licensePeriod)
            resultAmounts.add(license.sale.date, license.amountUSD * factor, license.amount * factor)
        }

        return RecurringRevenue(dateRange, resultAmounts)
    }

    private fun isValid(license: LicenseInfo): Boolean {
        // Only take licenses which are valid at the end of the date range, e.g. at the end of a month.
        // Licenses only valid at the start but expiring in the middle of a month do not contribute to recurring revenue.
        val validity = license.validity
        return validity != null && dateRange.end in validity
    }
}

class MonthlyRecurringRevenueTracker(
    timeRange: YearMonthDayRange,
    exchangeRates: ExchangeRates,
) : RecurringRevenueTracker(timeRange, exchangeRates) {
    private val annualToMonthly = BigDecimal.ONE.divide(BigDecimal.valueOf(12), 10, RoundingMode.HALF_UP)

    override fun periodFactor(licensePeriod: LicensePeriod): BigDecimal {
        return when (licensePeriod) {
            LicensePeriod.Monthly -> BigDecimal.ONE
            LicensePeriod.Annual -> annualToMonthly
            LicensePeriod.Perpetual -> BigDecimal.ONE
        }
    }
}

class AnnualRecurringRevenueTracker(
    timeRange: YearMonthDayRange,
    exchangeRates: ExchangeRates,
) : RecurringRevenueTracker(timeRange, exchangeRates) {
    private val monthlyToAnnual = BigDecimal.valueOf(12)

    override fun periodFactor(licensePeriod: LicensePeriod): BigDecimal {
        return when (licensePeriod) {
            LicensePeriod.Monthly -> monthlyToAnnual
            LicensePeriod.Annual -> BigDecimal.ONE
            LicensePeriod.Perpetual -> BigDecimal.ONE
        }
    }
}

data class RecurringRevenue(
    val dateRange: YearMonthDayRange,
    val amounts: MonetaryAmountTracker
)
