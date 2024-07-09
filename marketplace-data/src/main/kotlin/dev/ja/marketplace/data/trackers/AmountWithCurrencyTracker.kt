/*
 * Copyright (c) 2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.trackers

import dev.ja.marketplace.client.Amount
import dev.ja.marketplace.client.AmountWithCurrency
import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.exchangeRate.ExchangeRates
import java.util.*

/**
 * Tracks amounts with currency.
 */
class AmountWithCurrencyTracker(private val exchangeRates: ExchangeRates) {
    // currency code to amount
    private val amounts = TreeMap<String, Amount>()

    fun getValues(): List<AmountWithCurrency> {
        return when {
            amounts.isEmpty() -> emptyList()
            else -> amounts.entries.map { AmountWithCurrency(it.value, it.key) }
        }
    }

    suspend fun getConvertedResult(date: YearMonthDay): AmountWithCurrency {
        var convertedResult = AmountWithCurrency(Amount.ZERO, exchangeRates.targetCurrencyCode)
        for ((currency, amount) in amounts) {
            convertedResult += exchangeRates.convert(date, amount, currency)
        }
        return convertedResult
    }

    fun add(amount: AmountWithCurrency) {
        add(amount.amount, amount.currencyCode)
    }

    fun add(amount: Amount, currencyCode: String) {
        amounts.merge(currencyCode, amount) { sum, new -> sum + new }
    }

    operator fun plusAssign(paidAmount: AmountWithCurrency) {
        add(paidAmount)
    }
}