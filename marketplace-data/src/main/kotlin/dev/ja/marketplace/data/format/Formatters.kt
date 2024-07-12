/*
 * Copyright (c) 2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.format

import org.javamoney.moneta.format.AmountFormatParams
import org.javamoney.moneta.format.CurrencyStyle
import java.text.NumberFormat
import java.util.*
import javax.money.format.AmountFormatQueryBuilder
import javax.money.format.MonetaryAmountFormat
import javax.money.format.MonetaryFormats

object Formatters {
    val MonetaryAmount: MonetaryAmountFormat = createMoneyFormatter(Locale.getDefault())

    fun createMoneyFormatter(locale: Locale): MonetaryAmountFormat {
        return when {
            // by default, the format is like "USD1,000.23" without a space, which isn't readable
            locale.language == "en" -> MonetaryFormats.getAmountFormat(
                AmountFormatQueryBuilder
                    .of(locale)
                    .set(AmountFormatParams.PATTERN, "¤ ###,###.00")
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