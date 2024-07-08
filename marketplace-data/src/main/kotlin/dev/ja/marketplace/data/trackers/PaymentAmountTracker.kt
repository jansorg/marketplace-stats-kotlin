/*
 * Copyright (c) 2023-2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.trackers

import dev.ja.marketplace.client.Amount
import dev.ja.marketplace.client.Marketplace
import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.client.YearMonthDayRange
import java.math.BigDecimal

/**
 * Tracks the payment within a given range of dates.
 */
data class PaymentAmountTracker(val filterDateRange: YearMonthDayRange) {
    private var _totalAmountUSD: Amount = BigDecimal.ZERO
    private var _feesUSD: Amount = BigDecimal.ZERO

    val totalAmountUSD: Amount
        get() {
            return _totalAmountUSD
        }

    val feesAmountUSD: Amount
        get() {
            return _feesUSD
        }

    val paidAmountUSD: Amount
        get() {
            return _totalAmountUSD - _feesUSD
        }

    fun add(paymentDate: YearMonthDay, amountUSD: Amount) {
        if (paymentDate in filterDateRange) {
            _totalAmountUSD += amountUSD
            _feesUSD += Marketplace.feeAmount(paymentDate, amountUSD)
        }
    }
}