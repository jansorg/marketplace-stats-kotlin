/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.subscriptions

import dev.ja.marketplace.client.CustomerId
import dev.ja.marketplace.client.YearMonthDay
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * A payment of a customer in the display currency.
 */
data class CustomerPayment(val customer: CustomerId, val date: YearMonthDay, val amount: BigDecimal)

/**
 * The realized lifetime value (LTV) of the customers of a cohort: the average amount, which the customers
 * actually paid until 1, 2, ... years after the start of their first month with recurring revenue.
 */
data class RealizedLifetimeValue(
    val year: Int,
    val customers: Int,
    val years: List<RealizedLifetimeValueYear>,
) {
    companion object {
        fun of(
            revenue: CustomerRecurringRevenue,
            payments: Iterable<CustomerPayment>,
            today: YearMonthDay = YearMonthDay.now(),
        ): List<RealizedLifetimeValue> {
            val paymentsByCustomer = Int2ObjectOpenHashMap<MutableList<CustomerPayment>>()
            for (payment in payments) {
                paymentsByCustomer.computeIfAbsent(payment.customer) { mutableListOf() } += payment
            }

            return revenue.cohorts().map { (year, customers) ->
                val years = generateSequence(1) { it + 1 }
                    .map { years -> RealizedLifetimeValueYear.of(revenue, paymentsByCustomer, years, customers, today) }
                    .takeWhile { it != null }
                    .filterNotNull()
                    .toList()

                RealizedLifetimeValue(year, customers.size, years)
            }
        }
    }
}

data class RealizedLifetimeValueYear(
    val years: Int,
    /** Number of customers, whose period of [years] years already ended. */
    val customers: Int,
    /** Total amount, which these customers paid within the period. */
    val totalAmount: BigDecimal,
    /** `true` if the period of some customers of the cohort didn't end yet. */
    val isIncomplete: Boolean,
) {
    val averageAmount: BigDecimal
        get() = totalAmount.divide(BigDecimal.valueOf(customers.toLong()), 10, RoundingMode.HALF_UP)

    companion object {
        /**
         * @param customers The ID and the index of the first month of each customer.
         * @return `null` if the period of all customers didn't end yet.
         */
        fun of(
            revenue: CustomerRecurringRevenue,
            paymentsByCustomer: Int2ObjectOpenHashMap<MutableList<CustomerPayment>>,
            years: Int,
            customers: List<Pair<CustomerId, Int>>,
            today: YearMonthDay,
        ): RealizedLifetimeValueYear? {
            var count = 0
            var total = BigDecimal.ZERO
            var isIncomplete = false
            for ((customer, first) in customers) {
                // exclusive end of the period
                val end = revenue.months[first].add(years, 0, 0)
                if (end > today) {
                    isIncomplete = true
                    continue
                }

                count++
                paymentsByCustomer.get(customer)?.forEach { payment ->
                    if (payment.date < end) {
                        total += payment.amount
                    }
                }
            }

            return when (count) {
                0 -> null
                else -> RealizedLifetimeValueYear(years, count, total, isIncomplete)
            }
        }
    }
}
