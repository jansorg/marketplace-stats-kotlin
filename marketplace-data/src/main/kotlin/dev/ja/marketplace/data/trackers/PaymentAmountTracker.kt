/*
 * Copyright (c) 2023-2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.trackers

import dev.ja.marketplace.client.*
import dev.ja.marketplace.exchangeRate.ExchangeRates
import dev.ja.marketplace.services.Currency
import dev.ja.marketplace.util.isZero

/**
 * Tracks the payment within a given range of dates.
 */
data class PaymentAmountTracker(
    val filterDateRange: YearMonthDayRange,
    private val exchangeRates: ExchangeRates
) {
    private val total = AmountTargetCurrencyTracker(exchangeRates)
    private val fees = AmountTargetCurrencyTracker(exchangeRates)

    val isZero: Boolean get() = total.getTotalAmountUSD().isZero()

    val totalAmountUSD: Amount get() = total.getTotalAmountUSD()

    val totalAmount: AmountWithCurrency get() = total.getTotalAmount()

    val feesAmountUSD: Amount get() = fees.getTotalAmountUSD()

    val feesAmount: AmountWithCurrency get() = fees.getTotalAmount()

    val paidAmountUSD: Amount
        get() {
            return totalAmountUSD - feesAmountUSD
        }

    val paidAmount: AmountWithCurrency
        get() {
            return totalAmount - feesAmount
        }

    suspend fun add(paymentDate: YearMonthDay, amountUSD: Amount, amount: Amount, currency: Currency) {
        if (paymentDate in filterDateRange) {
            total.add(paymentDate, amountUSD, amount, currency)
            fees.add(paymentDate, Marketplace.feeAmount(paymentDate, amountUSD), Marketplace.feeAmount(paymentDate, amount), currency)
        }
    }
}