/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.subscriptions

import dev.ja.marketplace.client.CustomerId
import dev.ja.marketplace.client.LicenseId
import dev.ja.marketplace.client.LicenseInfo
import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.client.currency.MarketplaceCurrencies
import dev.ja.marketplace.client.model.CustomerType
import dev.ja.marketplace.client.model.LicensePeriod
import dev.ja.marketplace.exchangeRate.ExchangeRates
import it.unimi.dsi.fastutil.ints.Int2ObjectMap
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap
import org.javamoney.moneta.FastMoney
import java.math.BigDecimal
import java.math.RoundingMode
import javax.money.CurrencyUnit
import javax.money.MonetaryAmount

/**
 * The monthly recurring revenue (MRR) of each customer at the end of each month, in the display currency.
 *
 * It uses the rules of [dev.ja.marketplace.data.trackers.MonthlyRecurringRevenueTracker]: only paid subscription
 * licenses, which are valid at the end of a month, contribute. Only the latest sale of each license is used,
 * annual licenses contribute 1/12 of the billed amount. Amounts are converted at the date of the sale.
 *
 * The months start with the first month with recurring revenue and end with the current month,
 * whose values are still incomplete.
 */
class CustomerRecurringRevenue(
    licenses: Iterable<LicenseInfo>,
    private val exchangeRates: ExchangeRates,
    today: YearMonthDay = YearMonthDay.now(),
) {
    val currency: CurrencyUnit = exchangeRates.targetCurrency

    /** First day of each month, ordered by date. */
    val months: List<YearMonthDay>

    /** Index of the current month in [months], or `-1` if there are no months. */
    val currentMonthIndex: Int

    private val customerMrr: List<Int2ObjectMap<BigDecimal>>
    private val customerTypes = Int2ObjectOpenHashMap<CustomerType>()
    private val firstPaidMonthIndices = Int2ObjectOpenHashMap<Int>()

    init {
        val todayKey = monthKey(today)
        val latestLicenseSales = Int2ObjectOpenHashMap<Object2ObjectOpenHashMap<LicenseId, LicenseInfo>>()
        for (license in licenses) {
            val validity = license.validity
            if (validity == null || !license.isPaidLicense || !license.isSubscriptionLicense) {
                continue
            }

            customerTypes.put(license.sale.customer.code, license.sale.customer.type)

            // all months, which end within the validity of the license
            var monthEnd = YearMonthDay.lastOfMonth(validity.start.year, validity.start.month)
            while (monthEnd <= validity.end && monthKey(monthEnd) <= todayKey) {
                latestLicenseSales.computeIfAbsent(monthKey(monthEnd)) { Object2ObjectOpenHashMap() }
                    .merge(license.id, license) { old, new -> if (new.validity!!.end > old.validity!!.end) new else old }
                monthEnd = monthEnd.add(0, 1, 0).let { YearMonthDay.lastOfMonth(it.year, it.month) }
            }
        }

        val firstKey = latestLicenseSales.keys.minOrNull()
        if (firstKey == null) {
            months = emptyList()
            customerMrr = emptyList()
            currentMonthIndex = -1
        } else {
            months = (firstKey..todayKey).map { YearMonthDay.firstOfMonth(it / 12, it % 12 + 1) }
            customerMrr = (firstKey..todayKey).mapIndexed { index, key ->
                val result = Int2ObjectOpenHashMap<BigDecimal>()
                latestLicenseSales[key]?.values?.forEach { license ->
                    val customer = license.sale.customer.code
                    result.merge(customer, monthlyAmount(license), BigDecimal::add)
                    firstPaidMonthIndices.putIfAbsent(customer, index)
                }
                result
            }
            currentMonthIndex = months.lastIndex
        }
    }

    /** The MRR of each customer with recurring revenue at the end of the month. */
    fun customers(monthIndex: Int): Int2ObjectMap<BigDecimal> {
        return customerMrr.getOrNull(monthIndex) ?: Int2ObjectOpenHashMap()
    }

    fun mrr(monthIndex: Int, customer: CustomerId): BigDecimal {
        return customerMrr.getOrNull(monthIndex)?.get(customer) ?: BigDecimal.ZERO
    }

    fun totalMrr(monthIndex: Int): BigDecimal {
        return customers(monthIndex).values.fold(BigDecimal.ZERO, BigDecimal::add)
    }

    /** Index of the first month, in which the customer had recurring revenue. */
    fun firstPaidMonthIndex(customer: CustomerId): Int? {
        return firstPaidMonthIndices.get(customer)
    }

    /**
     * Groups the customers by the year of their first month with recurring revenue.
     *
     * @return The ID and the index of the first month of the customers of each year, ordered by year.
     */
    fun cohorts(): Map<Int, List<Pair<CustomerId, Int>>> {
        val customersByYear = sortedMapOf<Int, MutableList<Pair<CustomerId, Int>>>()
        for (monthIndex in months.indices) {
            for (customer in customers(monthIndex).keys) {
                if (firstPaidMonthIndex(customer) == monthIndex) {
                    customersByYear.getOrPut(months[monthIndex].year, ::mutableListOf) += customer to monthIndex
                }
            }
        }
        return customersByYear
    }

    fun customerType(customer: CustomerId): CustomerType? {
        return customerTypes.get(customer)
    }

    fun isCurrentMonth(monthIndex: Int): Boolean {
        return monthIndex == currentMonthIndex
    }

    /** Label of a month, e.g. `2026-03`, which charts recognize as month. */
    fun monthLabel(monthIndex: Int): String {
        val month = months[monthIndex]
        return String.format("%04d-%02d", month.year, month.month)
    }

    fun toAmount(value: BigDecimal): MonetaryAmount {
        return FastMoney.of(value.setScale(2, RoundingMode.HALF_UP), currency)
    }

    private fun monthlyAmount(license: LicenseInfo): BigDecimal {
        val amount = when (currency) {
            MarketplaceCurrencies.USD -> license.amountUSD
            license.amount.currency -> license.amount
            else -> exchangeRates.convert(license.sale.date, license.amount)
        }.number.numberValue(BigDecimal::class.java)

        return when (license.sale.licensePeriod) {
            LicensePeriod.Annual -> amount.divide(BigDecimal.valueOf(12), 10, RoundingMode.HALF_UP)
            LicensePeriod.Monthly, LicensePeriod.Perpetual -> amount
        }
    }

    private fun monthKey(date: YearMonthDay): Int {
        return date.year * 12 + date.month - 1
    }
}
