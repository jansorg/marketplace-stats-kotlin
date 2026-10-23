/*
 * Copyright (c) 2024-2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.exchangeRate

import com.github.benmanes.caffeine.cache.Caffeine
import dev.ja.marketplace.client.CacheAware
import dev.ja.marketplace.client.YearMonthDay
import org.javamoney.moneta.FastMoney
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.util.concurrent.TimeUnit
import javax.money.CurrencyUnit
import javax.money.Monetary
import javax.money.MonetaryAmount
import javax.money.MonetaryException

/**
 * Converts monetary amounts with the daily reference rates of the European Central Bank.
 */
class ExchangeRates(
    targetCurrencyCode: String,
    private val rateData: EcbExchangeRateData = EcbExchangeRateData.Default,
) : CacheAware {
    private data class CacheKey(val date: YearMonthDay, val baseCurrency: CurrencyUnit)

    private val cachedExchangeRates = Caffeine.newBuilder()
        .maximumSize(10_000)
        .expireAfterWrite(7, TimeUnit.DAYS)
        .build<CacheKey, BigDecimal>()

    val targetCurrency: CurrencyUnit = Monetary.getCurrency(targetCurrencyCode)

    override fun invalidateCache() {
        cachedExchangeRates.invalidateAll()
        rateData.invalidate()
    }

    fun convert(date: YearMonthDay, amount: MonetaryAmount): MonetaryAmount {
        if (amount.currency == targetCurrency) {
            return amount
        }

        val now = YearMonthDay.now()
        val factor = when {
            date >= now -> getExchangeRateFactorUncached(now, amount.currency)
            else -> getExchangeRateFactor(date, amount.currency)
        }

        val converted = factor
            .multiply(amount.number.numberValue(BigDecimal::class.java))
            .setScale(5, RoundingMode.HALF_UP)
        return FastMoney.of(converted, targetCurrency)
    }

    private fun getExchangeRateFactor(date: YearMonthDay, baseCurrency: CurrencyUnit): BigDecimal {
        val key = CacheKey(date, baseCurrency)
        cachedExchangeRates.getIfPresent(key)?.let { return it }

        val factor = getExchangeRateFactorUncached(date, baseCurrency)
        // The closest date with rates may change for dates after the latest published rates
        val lastDate = rateData.get().lastDate
        if (lastDate != null && date.toLocalDate() < lastDate) {
            cachedExchangeRates.put(key, factor)
        }
        return factor
    }

    private fun getExchangeRateFactorUncached(date: YearMonthDay, baseCurrency: CurrencyUnit): BigDecimal {
        val rates = rateData.get()
        // rates are not published on weekends and holidays
        val rateDate = rates.closestDate(date.toLocalDate())
            ?: throw MonetaryException("No ECB exchange rates available for ${date.asIsoString}")

        val baseCode = baseCurrency.currencyCode
        val termCode = targetCurrency.currencyCode
        val baseRate = rates.euroRate(baseCode, rateDate)
        val termRate = rates.euroRate(termCode, rateDate)
        if (baseRate == null || termRate == null) {
            throw MonetaryException("No ECB exchange rate available for $baseCode to $termCode on $rateDate")
        }

        // ECB rates are quoted as the amount of a currency for 1 EUR, other conversions are derived via EUR
        return when {
            baseCode == "EUR" -> termRate
            termCode == "EUR" -> reverse(baseRate)
            else -> reverse(baseRate).multiply(termRate)
        }
    }

    private fun reverse(rate: BigDecimal): BigDecimal {
        return BigDecimal.ONE.divide(rate, MathContext.DECIMAL64)
    }

}
