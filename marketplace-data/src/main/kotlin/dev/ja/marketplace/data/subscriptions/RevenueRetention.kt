/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.subscriptions

import dev.ja.marketplace.data.PercentageValue
import java.math.BigDecimal

/**
 * The retention of the customers with recurring revenue at the end of a month,
 * compared to the end of the same month in the previous year.
 */
data class RevenueRetention(
    /** Number of customers with recurring revenue a year ago. */
    val previousCustomers: Int,
    /** Number of these customers, who still have recurring revenue. */
    val retainedCustomers: Int,
    /** MRR of the customers a year ago. */
    val previousMrr: BigDecimal,
    /** Current MRR of the same customers, without new or returning customers. */
    val currentMrr: BigDecimal,
    /** Current MRR of the same customers, limited to their MRR a year ago, i.e. without expansion. */
    val currentMrrWithoutExpansion: BigDecimal,
) {
    val customerRetention: PercentageValue
        get() = PercentageValue.of(retainedCustomers, previousCustomers)

    /** Net revenue retention (NRR), includes expansion and contraction of the existing customers. */
    val netRevenueRetention: PercentageValue
        get() = PercentageValue.of(currentMrr, previousMrr)

    /** Gross revenue retention (GRR), includes contraction and churn, but not expansion. It's at most 100%. */
    val grossRevenueRetention: PercentageValue
        get() = PercentageValue.of(currentMrrWithoutExpansion, previousMrr)

    companion object {
        const val MONTHS: Int = 12

        /**
         * @return The retention at the end of the month, or `null` if there's no data of the month a year ago.
         */
        fun of(revenue: CustomerRecurringRevenue, monthIndex: Int): RevenueRetention? {
            if (monthIndex < MONTHS) {
                return null
            }

            val previous = revenue.customers(monthIndex - MONTHS)
            val current = revenue.customers(monthIndex)

            var retainedCustomers = 0
            var previousMrr = BigDecimal.ZERO
            var currentMrr = BigDecimal.ZERO
            var currentMrrWithoutExpansion = BigDecimal.ZERO
            for (entry in previous.int2ObjectEntrySet()) {
                previousMrr += entry.value

                val customerMrr = current.get(entry.intKey) ?: continue
                retainedCustomers++
                currentMrr += customerMrr
                currentMrrWithoutExpansion += customerMrr.min(entry.value)
            }

            return RevenueRetention(previous.size, retainedCustomers, previousMrr, currentMrr, currentMrrWithoutExpansion)
        }
    }
}
