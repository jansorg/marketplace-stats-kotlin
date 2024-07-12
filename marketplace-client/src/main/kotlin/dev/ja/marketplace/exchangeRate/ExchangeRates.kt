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
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate
import java.util.*
import javax.money.CurrencyUnit
import javax.money.Monetary
import javax.money.MonetaryAmount
import javax.money.convert.*

/**
 * Prefetched exchange rates.
 */
class ExchangeRates(targetCurrencyCode: String) {
    val targetCurrency: CurrencyUnit = Monetary.getCurrency(targetCurrencyCode)

    private val historicRateProvider: ExchangeRateProvider = ECBHistoricRateProvider()
    private val currentRateProvider: ExchangeRateProvider = ECBCurrentRateProvider()
    private val composedRateProvider = CompoundRateProvider(listOf(historicRateProvider, currentRateProvider))

    fun convert(date: YearMonthDay, amount: MonetaryAmount): MonetaryAmount {
        val now = YearMonthDay.now()
        val fixedDate = if (date > now) now else date
        val query = ConversionQueryBuilder
            .of()
            .setTermCurrency(targetCurrency)
            .set(Array<LocalDate>::class.java, createLookupDates(fixedDate))
            .build()
        val conversion = composedRateProvider.getCurrencyConversion(query)
        return conversion.applyConversion(amount, targetCurrency)
    }

    // fixed apply method to make it work with FastMoney
    private fun CurrencyConversion.applyConversion(amount: MonetaryAmount, termCurrency: CurrencyUnit): MonetaryAmount {
        if (termCurrency == amount.currency) {
            return amount
        }

        val rate: ExchangeRate = getExchangeRate(amount)
        if (Objects.isNull(rate) || amount.currency != rate.baseCurrency) {
            throw CurrencyConversionException(
                amount.currency,
                termCurrency, null
            )

        }
        val multiplied = rate.factor.numberValue(BigDecimal::class.java)
            .multiply(amount.number.numberValue(BigDecimal::class.java))
            .setScale(5, RoundingMode.HALF_UP)
        return FastMoney.of(multiplied, rate.currency)
//        return FastMoney.of(MoneyUtils.getBigDecimal(multiplied), rate.currency)//.with(MonetaryOperators.rounding(5))
//        return amount.factory.setCurrency(rate.currency).setNumber(multiplied).create().with(MonetaryOperators.rounding(5))
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