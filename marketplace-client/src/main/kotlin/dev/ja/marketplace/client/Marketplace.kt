/*
 * Copyright (c) 2023-2025 Joachim Ansorg.
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

    /**
     * Number of days after the end of the trial period, in which a purchase still counts as a converted trial.
     */
    const val TRIAL_CONVERSION_EXTRA_DAYS: Int = 30

    /**
     * Maximum number of days between the expiration of a license and the start of its renewal,
     * which is not counted as churn. It's unclear how JetBrains handles late renewals,
     * e.g. if the continuity discount is kept.
     */
    const val LICENSE_RENEWAL_GRACE_DAYS: Int = 90

    // That's the limited imposed by the Marketplace API
    const val MAX_SEARCH_RESULT_SIZE: Int = 20

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