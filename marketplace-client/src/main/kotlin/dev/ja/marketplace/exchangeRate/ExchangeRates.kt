/*
 * Copyright (c) 2024-2025 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.exchangeRate

import com.github.benmanes.caffeine.cache.Caffeine
import dev.ja.marketplace.client.CacheAware
import dev.ja.marketplace.client.YearMonthDay
import org.javamoney.moneta.FastMoney
import org.javamoney.moneta.convert.ecb.ECBCurrentRateProvider
import org.javamoney.moneta.convert.ecb.ECBHistoricRateProvider
import org.javamoney.moneta.spi.CompoundRateProvider
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import javax.money.CurrencyUnit
import javax.money.Monetary
import javax.money.MonetaryAmount
import javax.money.convert.ConversionQueryBuilder
import javax.money.convert.CurrencyConversionException
import javax.money.convert.ExchangeRate
import javax.money.convert.ExchangeRateProvider

/**
 * Prefetched exchange rates.
 */
class ExchangeRates(targetCurrencyCode: String) : CacheAware {
    private data class CacheKey(val date: YearMonthDay, val baseCurrency: CurrencyUnit)

    private val composedRateProvider = CompoundRateProvider(
        listOf<ExchangeRateProvider>(
            ECBHistoricRateProvider(),
            ECBCurrentRateProvider()
        )
    )

    private val cachedExchangeRates = Caffeine.newBuilder()
        .softValues()
        .maximumSize(1_000)
        .expireAfterWrite(7, TimeUnit.DAYS)
        .build<CacheKey, ExchangeRate>()

    val targetCurrency: CurrencyUnit = Monetary.getCurrency(targetCurrencyCode)

    override fun invalidateCache() {
        cachedExchangeRates.invalidateAll()
    }

    fun convert(date: YearMonthDay, amount: MonetaryAmount): MonetaryAmount {
        if (amount.currency == targetCurrency) {
            return amount
        }

        val now = YearMonthDay.now()
        val exchangeRate = when {
            date >= now -> getExchangeRateUncached(now, amount)
            else -> cachedExchangeRates.get(CacheKey(date, amount.currency)) {
                getExchangeRateUncached(date, amount)
            }
        }

        return applyConversion(exchangeRate, amount, targetCurrency)
    }

    private fun getExchangeRateUncached(
        fixedDate: YearMonthDay,
        amount: MonetaryAmount
    ): ExchangeRate {
        val query = ConversionQueryBuilder
            .of()
            .setTermCurrency(targetCurrency)
            .set(Array<LocalDate>::class.java, createLookupDates(fixedDate))
            .build()
        return composedRateProvider.getCurrencyConversion(query).getExchangeRate(amount)
    }

    // fixed apply method to make it work with FastMoney
    private fun applyConversion(rate: ExchangeRate?, amount: MonetaryAmount, termCurrency: CurrencyUnit): MonetaryAmount {
        val baseCurrency = amount.currency
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
        date.add(0, 0, -3).toLocalDate(),
        date.add(0, 0, 1).toLocalDate(),
        date.add(0, 0, 2).toLocalDate(),
    )
}