/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.subscriptions

import dev.ja.marketplace.client.CustomerId
import dev.ja.marketplace.client.model.CustomerType
import it.unimi.dsi.fastutil.ints.Int2ObjectMap
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * The average MRR per customer (ARPA) with recurring revenue at the end of a month, also by customer type.
 */
data class AverageRevenuePerCustomer(
    val customers: Int,
    val mrr: BigDecimal,
    val customersByType: Map<CustomerType, Int>,
    val mrrByType: Map<CustomerType, BigDecimal>,
) {
    val average: BigDecimal?
        get() = average(mrr, customers)

    fun average(type: CustomerType): BigDecimal? {
        return average(mrrByType[type] ?: BigDecimal.ZERO, customersByType[type] ?: 0)
    }

    private fun average(mrr: BigDecimal, customers: Int): BigDecimal? {
        return when (customers) {
            0 -> null
            else -> mrr.divide(BigDecimal.valueOf(customers.toLong()), 10, RoundingMode.HALF_UP)
        }
    }

    companion object {
        fun of(revenue: CustomerRecurringRevenue, monthIndex: Int): AverageRevenuePerCustomer {
            return of(revenue.customers(monthIndex), revenue::customerType)
        }

        /**
         * @param customers The MRR of each customer at the end of the period.
         */
        fun of(customers: Int2ObjectMap<BigDecimal>, customerType: (CustomerId) -> CustomerType?): AverageRevenuePerCustomer {
            var mrr = BigDecimal.ZERO
            val customersByType = mutableMapOf<CustomerType, Int>()
            val mrrByType = mutableMapOf<CustomerType, BigDecimal>()
            for (entry in customers.int2ObjectEntrySet()) {
                mrr += entry.value
                val type = customerType(entry.intKey) ?: continue
                customersByType.merge(type, 1, Int::plus)
                mrrByType.merge(type, entry.value, BigDecimal::add)
            }

            return AverageRevenuePerCustomer(customers.size, mrr, customersByType, mrrByType)
        }
    }
}
