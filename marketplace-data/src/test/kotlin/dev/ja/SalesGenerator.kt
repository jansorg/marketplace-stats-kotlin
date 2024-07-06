/*
 * Copyright (c) 2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja

import dev.ja.marketplace.TestCustomers
import dev.ja.marketplace.client.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

object SalesGenerator {
    fun createSale(
        type: LicensePeriod = LicensePeriod.Annual,
        saleDate: YearMonthDay? = null,
        validity: YearMonthDayRange? = null,
        customer: CustomerInfo? = null,
        amount: Amount? = null,
        currency: Currency = Currency.USD,
        saleType: PluginSaleItemType = PluginSaleItemType.New,
    ): PluginSale {
        val start = saleDate ?: nextDate()
        val usedValidity = validity
            ?: if (type == LicensePeriod.Annual) start.rangeTo(start.add(1, 0, -1)) else start.rangeTo(start.add(0, 1, -1))

        val usedAmount = amount ?: randomAmount()
        return PluginSale(
            nextRef(),
            start,
            usedAmount,
            usedAmount,
            currency,
            type,
            customer ?: TestCustomers.PersonDummy,
            null,
            listOf(PluginSaleItem(saleType, listOf(nextLicenseId()), usedValidity, usedAmount, usedAmount, emptyList()))
        )
    }

    private val refId = AtomicInteger()

    private fun nextRef(): String {
        return "sale-${refId.getAndIncrement()}"
    }

    private val licenseId = AtomicInteger()

    private fun nextLicenseId(): String {
        return "license-${licenseId.getAndIncrement()}"
    }

    private var date = YearMonthDay(2021, 1, 1)
    private fun nextDate(): YearMonthDay {
        date = date.add(0, 1, 0)
        return date
    }

    private fun randomAmount(): Amount {
        return Random.nextInt(50).toBigDecimal()
    }
}