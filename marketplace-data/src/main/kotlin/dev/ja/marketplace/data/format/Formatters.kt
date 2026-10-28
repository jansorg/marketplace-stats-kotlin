/*
 * Copyright (c) 2024-2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.format

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import org.javamoney.moneta.format.AmountFormatParams
import org.javamoney.moneta.format.CurrencyStyle
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.util.*
import javax.money.MonetaryAmount
import javax.money.format.AmountFormatQueryBuilder
import javax.money.format.MonetaryAmountFormat
import javax.money.format.MonetaryFormats

object Formatters {
    val MonetaryAmount: MonetaryAmountFormat = createMoneyFormatter(Locale.getDefault())

    /**
     * The formatted amounts by amount, see [formatAmount]. The same amounts occur many times, e.g. the prices of the
     * licenses of a list, the format of Moneta allocates much memory for each call. Amounts converted with the
     * exchange rate of each day are mostly unique, the cache keeps the frequent amounts (W-TinyLFU of Caffeine).
     */
    private val formattedAmounts: Cache<MonetaryAmount, String> = Caffeine.newBuilder()
        .maximumSize(MAX_CACHED_AMOUNTS)
        .build()

    /**
     * @return The amount with the format of [MonetaryAmount], e.g. `1,234.50 USD`. The results are cached by the
     *  amount, the [MAX_CACHED_AMOUNTS] most frequent ones.
     */
    fun formatAmount(amount: MonetaryAmount): String {
        formattedAmounts.getIfPresent(amount)?.let { return it }
        val formatted = MonetaryAmount.format(amount)
        formattedAmounts.put(amount, formatted)
        return formatted
    }

    private const val MAX_CACHED_AMOUNTS = 20_000L

    private val numberLocale = Locale.getDefault(Locale.Category.FORMAT)

    // DecimalFormat isn't thread-safe. The formatters are cached, because String.format creates a new formatter
    // for each call, which is expensive for large tables.
    private val decimalFormat = ThreadLocal.withInitial { createNumberFormat(numberLocale, 2) }
    private val integerFormat = ThreadLocal.withInitial { createNumberFormat(numberLocale, 0) }

    /** Same output as `String.format("%,.2f", value)`. */
    fun formatDecimal(value: BigDecimal): String {
        return decimalFormat.get().format(value)
    }

    /** Same output as `String.format("%,.2f", value)`. */
    fun formatDecimal(value: Double): String {
        // BigDecimal can't represent these values, the zero would lose its sign
        if (!value.isFinite() || value.toRawBits() == NEGATIVE_ZERO_BITS) {
            return String.format("%,.2f", value)
        }
        // like String.format, the shortest decimal representation of the value is rounded
        return decimalFormat.get().format(BigDecimal.valueOf(value))
    }

    /** Same output as `String.format("%,d", value)`. */
    fun formatInteger(value: BigInteger): String {
        return integerFormat.get().format(value)
    }

    /** @return The number with the grouping of the locale, e.g. for texts like `4,155 customers`. */
    fun formatInteger(value: Long): String {
        return integerFormat.get().format(value)
    }

    /** @see formatInteger */
    fun formatInteger(value: Int): String = formatInteger(value.toLong())

    /** @return The size of a file in bytes, KB or MB (1,024 bytes per KB), e.g. `1.25 MB`. */
    fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "${formatInteger(bytes)} bytes"
            bytes < 1024 * 1024 -> "${formatDecimal(bytes / 1024.0)} KB"
            else -> "${formatDecimal(bytes / (1024.0 * 1024.0))} MB"
        }
    }

    /** Same output as `String.format("%,.2f %%", value)`, with a thin space before the percent sign. */
    fun formatPercentage(value: BigDecimal): String {
        return formatDecimal(value) + "\u2009%"
    }

    private val NEGATIVE_ZERO_BITS = (-0.0).toRawBits()

    /**
     * A format with the same output as the `%,d` and `%,.<digits>f` conversions of [String.format].
     */
    internal fun createNumberFormat(locale: Locale, fractionDigits: Int): DecimalFormat {
        val pattern = if (fractionDigits == 0) "#,##0" else "#,##0." + "0".repeat(fractionDigits)
        return DecimalFormat(pattern, DecimalFormatSymbols.getInstance(locale)).apply {
            groupingSize = (NumberFormat.getIntegerInstance(locale) as? DecimalFormat)?.groupingSize ?: 3
            roundingMode = RoundingMode.HALF_UP
            // String.format always uses the ASCII minus sign
            negativePrefix = "-"
        }
    }

    fun createMoneyFormatter(locale: Locale): MonetaryAmountFormat {
        return when {
            // by default, the format is like "USD1,000.23" without a space, which isn't readable
            locale.language == "en" -> MonetaryFormats.getAmountFormat(
                AmountFormatQueryBuilder
                    .of(locale)
                    .set(AmountFormatParams.PATTERN, "¤ #,##0.00")
                    .build()
            )

            else -> MonetaryFormats.getAmountFormat(
                AmountFormatQueryBuilder
                    .of(locale)
                    .set(CurrencyStyle.CODE)
                    .build()
            )
        }
    }
}