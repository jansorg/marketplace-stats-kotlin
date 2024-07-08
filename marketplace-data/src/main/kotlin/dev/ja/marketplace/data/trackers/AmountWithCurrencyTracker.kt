/*
 * Copyright (c) 2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.trackers

import dev.ja.marketplace.client.Amount
import dev.ja.marketplace.client.AmountWithCurrency
import dev.ja.marketplace.services.Currency
import java.util.TreeMap

/**
 * Tracks amounts with currency.
 */
class AmountWithCurrencyTracker {
    // currency code to amount
    private val amounts = TreeMap<String, Amount>()

    fun getValues(): List<AmountWithCurrency> {
        return amounts.entries.map { AmountWithCurrency(it.value, it.key) }
    }

    fun getAmount(currency: Currency): Amount {
        return amounts[currency.isoCode] ?: Amount.ZERO
    }

    fun add(amount: AmountWithCurrency) {
        add(amount.amount, amount.currency)
    }

    fun add(amount: Amount, currency: Currency) {
        amounts.merge(currency.isoCode, amount) { sum, new -> sum + new }
    }

    operator fun plusAssign(paidAmount: AmountWithCurrency) {
        add(paidAmount)
    }
}