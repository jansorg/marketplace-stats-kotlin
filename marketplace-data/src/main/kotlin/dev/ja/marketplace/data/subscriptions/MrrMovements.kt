/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.subscriptions

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * The changes of the MRR from the end of the previous month to the end of a month, grouped by their cause.
 * All amounts are positive.
 */
data class MrrMovements(
    val mrr: BigDecimal,
    /** MRR of customers, who paid for the first time. */
    val new: BigDecimal,
    /** Additional MRR of existing customers, e.g. more licenses or a higher price. */
    val expansion: BigDecimal,
    /** Lost MRR of remaining customers, e.g. fewer licenses or a lower price. */
    val contraction: BigDecimal,
    /** Lost MRR of customers without recurring revenue at the end of the month. */
    val churned: BigDecimal,
    /** MRR of returning customers, who had recurring revenue before, but not at the end of the previous month. */
    val reactivation: BigDecimal,
) {
    val netChange: BigDecimal
        get() = new + expansion + reactivation - contraction - churned

    /**
     * The SaaS quick ratio: gained MRR divided by lost MRR. A value below 1 means that the MRR is shrinking.
     */
    val quickRatio: BigDecimal?
        get() {
            val lost = contraction + churned
            return when (lost.signum()) {
                0 -> null
                else -> (new + expansion + reactivation).divide(lost, 2, RoundingMode.HALF_UP)
            }
        }

    companion object {
        fun of(revenue: CustomerRecurringRevenue, monthIndex: Int): MrrMovements {
            val current = revenue.customers(monthIndex)
            val previous = revenue.customers(monthIndex - 1)

            var new = BigDecimal.ZERO
            var expansion = BigDecimal.ZERO
            var contraction = BigDecimal.ZERO
            var churned = BigDecimal.ZERO
            var reactivation = BigDecimal.ZERO

            for (entry in current.int2ObjectEntrySet()) {
                val customer = entry.intKey
                val mrr = entry.value
                val previousMrr = previous.get(customer)
                when {
                    previousMrr == null && revenue.firstPaidMonthIndex(customer) == monthIndex -> new += mrr
                    previousMrr == null -> reactivation += mrr
                    mrr > previousMrr -> expansion += mrr - previousMrr
                    mrr < previousMrr -> contraction += previousMrr - mrr
                }
            }

            for (entry in previous.int2ObjectEntrySet()) {
                if (!current.containsKey(entry.intKey)) {
                    churned += entry.value
                }
            }

            return MrrMovements(revenue.totalMrr(monthIndex), new, expansion, contraction, churned, reactivation)
        }
    }
}
