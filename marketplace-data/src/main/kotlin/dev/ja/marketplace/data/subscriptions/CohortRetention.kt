/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.subscriptions

import dev.ja.marketplace.data.PercentageValue
import java.math.BigDecimal

/**
 * The customers, who had recurring revenue for the first time in the same year,
 * and how many of them still had recurring revenue at their anniversaries.
 */
data class CohortRetention(
    val year: Int,
    val customers: Int,
    /** MRR of the customers at the end of their first month with recurring revenue. */
    val initialMrr: BigDecimal,
    /** Retention after 1, 2, ... years. */
    val anniversaries: List<CohortAnniversary>,
) {
    companion object {
        /**
         * Groups the customers by the year of their first month with recurring revenue.
         * The anniversary of a customer is the end of the same month in a later year,
         * e.g. the end of March 2025 and 2026 for a customer, who paid for the first time in March 2024.
         */
        fun of(revenue: CustomerRecurringRevenue): List<CohortRetention> {
            val customersByYear = sortedMapOf<Int, MutableList<Pair<Int, Int>>>()
            for (monthIndex in revenue.months.indices) {
                for (customer in revenue.customers(monthIndex).keys) {
                    if (revenue.firstPaidMonthIndex(customer) == monthIndex) {
                        customersByYear.getOrPut(revenue.months[monthIndex].year, ::mutableListOf) += customer to monthIndex
                    }
                }
            }

            return customersByYear.map { (year, customers) ->
                val initialMrr = customers.fold(BigDecimal.ZERO) { sum, (customer, first) -> sum + revenue.mrr(first, customer) }

                val anniversaries = generateSequence(1) { it + 1 }
                    .map { years -> CohortAnniversary.of(revenue, years, customers) }
                    .takeWhile { it != null }
                    .filterNotNull()
                    .toList()

                CohortRetention(year, customers.size, initialMrr, anniversaries)
            }
        }
    }
}

data class CohortAnniversary(
    val years: Int,
    /** Number of customers, whose anniversary is not in the future. */
    val customers: Int,
    /** Number of these customers with recurring revenue at their anniversary. */
    val retainedCustomers: Int,
    /** MRR of these customers in their first month. */
    val initialMrr: BigDecimal,
    /** MRR of these customers at their anniversary. */
    val mrr: BigDecimal,
    /** `true` if the anniversary of some customers of the cohort is still in the future. */
    val isIncomplete: Boolean,
) {
    val customerRetention: PercentageValue
        get() = PercentageValue.of(retainedCustomers, customers)

    val revenueRetention: PercentageValue
        get() = PercentageValue.of(mrr, initialMrr)

    companion object {
        /**
         * @param customers The ID and the index of the first month of each customer.
         * @return `null` if the anniversary of all customers is in the future.
         */
        fun of(revenue: CustomerRecurringRevenue, years: Int, customers: List<Pair<Int, Int>>): CohortAnniversary? {
            var count = 0
            var retained = 0
            var initialMrr = BigDecimal.ZERO
            var mrr = BigDecimal.ZERO
            var isIncomplete = false
            for ((customer, first) in customers) {
                val anniversary = first + 12 * years
                if (anniversary > revenue.currentMonthIndex) {
                    isIncomplete = true
                    continue
                }

                // values of the current month may still change
                isIncomplete = isIncomplete || revenue.isCurrentMonth(anniversary)
                count++
                initialMrr += revenue.mrr(first, customer)
                val customerMrr = revenue.mrr(anniversary, customer)
                if (customerMrr.signum() > 0) {
                    retained++
                    mrr += customerMrr
                }
            }

            return when (count) {
                0 -> null
                else -> CohortAnniversary(years, count, retained, initialMrr, mrr, isIncomplete)
            }
        }
    }
}
