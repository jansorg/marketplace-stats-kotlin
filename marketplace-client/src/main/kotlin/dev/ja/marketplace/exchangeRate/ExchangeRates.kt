/*
 * Copyright (c) 2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.exchangeRate

import dev.ja.marketplace.client.YearMonthDay
import org.javamoney.moneta.FastMoney
import org.javamoney.moneta.convert.ecb.ECBCurrentRateProvider
import org.javamoney.moneta.convert.ecb.ECBHistoricRateProvider
import org.javamoney.moneta.spi.CompoundRateProvider
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
import javax.money.CurrencyUnit
import javax.money.Monetary
import javax.money.MonetaryAmount
import javax.money.convert.*

/**
 * Prefetched exchange rates.
 */
class ExchangeRates(targetCurrencyCode: String) {
    val targetCurrency: CurrencyUnit = Monetary.getCurrency(targetCurrencyCode)

    private val composedRateProvider = CompoundRateProvider(
        listOf<ExchangeRateProvider>(
            ECBHistoricRateProvider(),
            ECBCurrentRateProvider()
        )
    )

    fun convert(date: YearMonthDay, amount: MonetaryAmount): MonetaryAmount {
        if (amount.currency == targetCurrency) {
            return amount
        }

        val now = YearMonthDay.now()
        val fixedDate = if (date > now) now else date
        val query = ConversionQueryBuilder
            .of()
            .setTermCurrency(targetCurrency)
            .set(Array<LocalDate>::class.java, createLookupDates(fixedDate))
            .build()
        return composedRateProvider.getCurrencyConversion(query).applyConversion(amount, targetCurrency)
    }

    private val cachedExchangeRates = ConcurrentHashMap<CurrencyUnit, ExchangeRate>()

    // fixed apply method to make it work with FastMoney
    private fun CurrencyConversion.applyConversion(amount: MonetaryAmount, termCurrency: CurrencyUnit): MonetaryAmount {
        val baseCurrency = amount.currency
        val rate = cachedExchangeRates.getOrPut(baseCurrency) {
            getExchangeRate(amount)
        }

        if (rate == null || baseCurrency != rate.baseCurrency) {
            throw CurrencyConversionException(baseCurrency, termCurrency, null)
        }

        val multiplied = rate.factor.numberValue(BigDecimal::class.java)
            .multiply(amount.number.numberValue(BigDecimal::class.java))
            .setScale(5, RoundingMode.HALF_UP)
        return FastMoney.of(multiplied, rate.currency)
    }

    // requested date with several fallback to make up to weekends
    private fun createLookupDates(date: YearMonthDay) = arrayOf(
        date.toLocalDate(),
        date.add(0, 0, -1).toLocalDate(),
        date.add(0, 0, -2).toLocalDate(),
        date.add(0, 0, 1).toLocalDate(),
        date.add(0, 0, 2).toLocalDate(),
    )
}