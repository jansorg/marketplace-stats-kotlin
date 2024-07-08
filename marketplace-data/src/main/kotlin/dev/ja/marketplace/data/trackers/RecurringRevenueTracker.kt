/*
 * Copyright (c) 2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.trackers

import dev.ja.marketplace.client.*
import dev.ja.marketplace.data.LicenseId
import dev.ja.marketplace.data.LicenseInfo
import java.math.BigDecimal
import java.util.*

/**
 * Tracks recurring revenue for a given time period, e.g. one month or one year.
 */
abstract class RecurringRevenueTracker(
    protected val dateRange: YearMonthDayRange,
    protected val pluginInfo: MarketplacePluginInfo
) {
    private val latestSales = TreeMap<LicenseId, LicenseInfo>()
    protected val continuityTracker = ContinuityDiscountTracker()

    protected abstract fun dateRangeSubscriptionPrice(license: LicenseInfo): Amount

    /**
     * @return The expected continuity discount for the next month or year.
     */
    protected abstract fun nextContinuityDiscountFactor(license: LicenseInfo): Double

    fun processLicenseSale(licenseInfo: LicenseInfo) {
        if (!licenseInfo.isPaidLicense) {
            return
        }

        // track all, not just those in the filtered revenue range
        continuityTracker.process(licenseInfo)

        if (isValid(licenseInfo)) {
            // only keep the latest valid license sale
            latestSales.merge(licenseInfo.id, licenseInfo) { old, new ->
                when {
                    new.validity > old.validity -> new
                    else -> old
                }
            }
        }
    }

    fun getResult(): RecurringRevenue {
        var result = Amount(0)

        for ((_, license) in latestSales) {
            val rangeBasePrice = dateRangeSubscriptionPrice(license)
            val continuityFactor = nextContinuityDiscountFactor(license)
            val discounts = otherDiscountsFactor(license) * continuityFactor

            result += Marketplace.paidAmount(license.validity.end, rangeBasePrice * discounts.toBigDecimal())
        }

        return RecurringRevenue(dateRange, result)
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
    pluginInfo: MarketplacePluginInfo,
) : RecurringRevenueTracker(timeRange, pluginInfo) {

    // annual subscription is 12 * monthly subscription price, calculated for a single month
    private val annualToMonthlyFactor = BigDecimal.valueOf(10.0 / 12.0)

    override fun nextContinuityDiscountFactor(license: LicenseInfo): Double {
        return continuityTracker.nextContinuityFactor(license.id, license.validity.start.add(0, 1, 0))
    }

    override fun dateRangeSubscriptionPrice(license: LicenseInfo): Amount {
        val basePrice = when (license.sale.customer.type) {
            CustomerType.Personal -> pluginInfo.individualPrice
            CustomerType.Organization -> pluginInfo.businessPrice
        }

        return when (license.sale.licensePeriod) {
            LicensePeriod.Monthly -> basePrice
            LicensePeriod.Annual -> basePrice * annualToMonthlyFactor
        }
    }
}

class AnnualRecurringRevenueTracker(
    timeRange: YearMonthDayRange,
    pluginInfo: MarketplacePluginInfo,
) : RecurringRevenueTracker(timeRange, pluginInfo) {
    // annual subscription price is 10 * monthly subscription price
    private val annualFactor = BigDecimal.valueOf(10)

    // monthly subscription is paid 12 times per year
    private val monthlyToAnnualFactor = BigDecimal.valueOf(12)

    override fun nextContinuityDiscountFactor(license: LicenseInfo): Double {
        return continuityTracker.nextContinuityFactor(license.id, license.validity.start.add(1, 0, 0))
    }

    override fun dateRangeSubscriptionPrice(license: LicenseInfo): Amount {
        val basePrice = when (license.sale.customer.type) {
            CustomerType.Personal -> pluginInfo.individualPrice
            CustomerType.Organization -> pluginInfo.businessPrice
        }

        return when (license.sale.licensePeriod) {
            LicensePeriod.Monthly -> basePrice * monthlyToAnnualFactor
            LicensePeriod.Annual -> basePrice * annualFactor
        }
    }
}

data class RecurringRevenue(
    val dateRange: YearMonthDayRange,
    val averageAmount: Amount
)