/*
 * Copyright (c) 2023-2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client

import io.ktor.http.*
import kotlinx.datetime.TimeZone
import java.math.BigDecimal
import javax.money.MonetaryAmount

val MarketplaceTimeZone = TimeZone.of("Europe/Berlin")

object Marketplace {
    private val FeeChangeTimestamp = YearMonthDay(2020, 7, 1)
    private val lowFeeFactor = BigDecimal("0.05")
    private val regularFeeFactor = BigDecimal("0.15")

    val Birthday = YearMonthDay(2019, 6, 25)
    const val MAX_TRIAL_DAYS_DEFAULT: Int = 30

    const val MAX_SEARCH_RESULT_SIZE: Int = 10_000

    val MarketplaceFrontendUrl: Url = Url("https://plugins.jetbrains.com")

    const val HOSTNAME = "plugins.jetbrains.com"
    const val API_PATH = "api"

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