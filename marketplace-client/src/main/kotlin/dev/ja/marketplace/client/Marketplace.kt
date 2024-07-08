/*
 * Copyright (c) 2023-2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client

import kotlinx.datetime.TimeZone

val MarketplaceTimeZone = TimeZone.of("Europe/Berlin")

object Marketplace {
    private val FeeChangeTimestamp = YearMonthDay(2020, 7, 1)

    val Birthday = YearMonthDay(2019, 6, 25)

    fun feeAmount(date: YearMonthDay, amount: Amount): Amount {
        return when {
            date < FeeChangeTimestamp -> amount * 0.05.toBigDecimal()
            else -> amount * 0.15.toBigDecimal()
        }
    }

    fun paidAmount(date: YearMonthDay, amount: Amount): Amount {
        return amount - feeAmount(date, amount)
    }

    fun paidAmount(date: YearMonthDay, amount: AmountWithCurrency): AmountWithCurrency {
        return amount - feeAmount(date, amount.amount)
    }
}