/*
 * Copyright (c) 2024-2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.trackers

import dev.ja.marketplace.client.currency.MarketplaceCurrencies
import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.exchangeRate.ExchangeRates
import org.javamoney.moneta.FastMoney
import java.math.BigDecimal
import javax.money.CurrencyUnit
import javax.money.MonetaryAmount

/**
 * Tracks payments with currencies and converts them into the target currency.
 */
class MonetaryAmountTracker(private val exchangeRates: ExchangeRates) {
    private val sumUSD = MonetarySum(MarketplaceCurrencies.USD)
    private val sumTargetCurrency = MonetarySum(exchangeRates.targetCurrency)

    fun add(date: YearMonthDay, amountUSD: MonetaryAmount, amount: MonetaryAmount) {
        sumUSD += amountUSD
        sumTargetCurrency += when (exchangeRates.targetCurrency) {
            MarketplaceCurrencies.USD -> amountUSD
            amount.currency -> amount
            else -> exchangeRates.convert(date, amount)
        }
    }

    /**
     * Adds the amounts of another tracker with the same target currency, e.g. of another plugin.
     */
    fun add(other: MonetaryAmountTracker) {
        sumUSD += other.getTotalAmountUSD()
        sumTargetCurrency += other.getTotalAmount()
    }

    fun getTotalAmountUSD(): MonetaryAmount {
        return sumUSD.amount
    }

    fun getTotalAmount(): MonetaryAmount {
        return sumTargetCurrency.amount
    }
}

/**
 * Sum of amounts in a single currency.
 *
 * [FastMoney.add] converts both amounts to [BigDecimal] to validate them and converts the result back,
 * adding the numbers is several times faster. The sum is a [FastMoney] like the sum of [FastMoney.add].
 */
private class MonetarySum(private val currency: CurrencyUnit) {
    private var sum: BigDecimal = BigDecimal.ZERO

    val amount: MonetaryAmount
        get() = FastMoney.of(sum, currency)

    operator fun plusAssign(value: MonetaryAmount) {
        require(value.currency == currency) { "Currency mismatch: ${value.currency} != $currency" }
        sum = sum.add(value.number.numberValue(BigDecimal::class.java))
    }
}