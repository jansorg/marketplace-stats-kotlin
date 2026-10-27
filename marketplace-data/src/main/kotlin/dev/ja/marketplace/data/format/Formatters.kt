/*
 * Copyright (c) 2024-2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.format

import org.javamoney.moneta.format.AmountFormatParams
import org.javamoney.moneta.format.CurrencyStyle
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.util.*
import javax.money.format.AmountFormatQueryBuilder
import javax.money.format.MonetaryAmountFormat
import javax.money.format.MonetaryFormats

object Formatters {
    val MonetaryAmount: MonetaryAmountFormat = createMoneyFormatter(Locale.getDefault())

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