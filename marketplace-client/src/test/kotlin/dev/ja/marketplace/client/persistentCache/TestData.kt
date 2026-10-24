/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client.persistentCache

import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.client.YearMonthDayRange
import dev.ja.marketplace.client.fastMoneyOf
import dev.ja.marketplace.client.model.*

internal fun testSale(
    ref: String,
    date: YearMonthDay,
    amountUSD: Double = 100.0,
    currency: String = "USD",
    amount: Double = amountUSD,
    reseller: ResellerInfo? = null,
): PluginSale {
    val usd = fastMoneyOf(amountUSD, "USD")
    val local = if (currency == "USD") usd else fastMoneyOf(amount, currency)
    return PluginSale(
        ref,
        date,
        local,
        usd,
        LicensePeriod.Annual,
        CustomerInfo(42, "Germany", CustomerType.Organization, "Customer Ltd."),
        reseller,
        listOf(
            SubscriptionPluginSaleItem(
                PluginSaleItemType.New,
                listOf("LICENSE-$ref"),
                local,
                usd,
                listOf(PluginSaleItemDiscount("10% continuity discount", 10.0)),
                YearMonthDayRange(date, date.add(1, 0, -1)),
            )
        ),
    )
}

internal fun testTrial(ref: String, date: YearMonthDay): PluginTrial {
    return PluginTrial(ref, date, CustomerInfo(7, "France", CustomerType.Individual, null))
}

internal fun day(year: Int, month: Int, day: Int): YearMonthDay = YearMonthDay(year, month, day)
