/*
 * Copyright (c) 2023-2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client

import kotlinx.datetime.TimeZone
import java.math.BigDecimal
import javax.money.MonetaryAmount

val MarketplaceTimeZone = TimeZone.of("Europe/Berlin")

object Marketplace {
    private val FeeChangeTimestamp = YearMonthDay(2020, 7, 1)
    private val lowFeeFactor = BigDecimal("0.05")
    private val regularFeeFactor = BigDecimal("0.15")

    val Birthday = YearMonthDay(2019, 6, 25)

    fun feeAmount(date: YearMonthDay, amount: MonetaryAmount): MonetaryAmount {
        return when {
            date < FeeChangeTimestamp -> amount * lowFeeFactor
            else -> amount * regularFeeFactor
        }
    }

    fun paidAmount(date: YearMonthDay, amount: MonetaryAmount): MonetaryAmount {
        return amount - feeAmount(date, amount)
    }
}