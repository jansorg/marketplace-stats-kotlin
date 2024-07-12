/*
 * Copyright (c) 2023-2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.trackers

import dev.ja.marketplace.client.*
import dev.ja.marketplace.exchangeRate.ExchangeRates
import javax.money.MonetaryAmount

/**
 * Tracks the payment within a given range of dates.
 */
data class PaymentAmountTracker(val filterDateRange: YearMonthDayRange, private val exchangeRates: ExchangeRates) {
    private val total = MonetaryAmountTracker(exchangeRates)
    private val fees = MonetaryAmountTracker(exchangeRates)

    val isZero: Boolean get() = total.getTotalAmountUSD().isZero()

    val totalAmountUSD: MonetaryAmount get() = total.getTotalAmountUSD()

    val totalAmount: MonetaryAmount get() = total.getTotalAmount()

    val feesAmountUSD: MonetaryAmount get() = fees.getTotalAmountUSD()

    val feesAmount: MonetaryAmount get() = fees.getTotalAmount()

    val paidAmountUSD: MonetaryAmount
        get() {
            return totalAmountUSD - feesAmountUSD
        }

    val paidAmount: MonetaryAmount
        get() {
            return totalAmount - feesAmount
        }

    suspend fun add(paymentDate: YearMonthDay, amountUSD: MonetaryAmount, amount: MonetaryAmount) {
        if (paymentDate in filterDateRange) {
            total.add(paymentDate, amountUSD, amount)
            fees.add(paymentDate, Marketplace.feeAmount(paymentDate, amountUSD), Marketplace.feeAmount(paymentDate, amount))
        }
    }
}