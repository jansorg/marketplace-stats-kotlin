/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.subscriptions

import dev.ja.SalesGenerator
import dev.ja.marketplace.client.LicenseInfo
import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.client.currency.MarketplaceCurrencies
import dev.ja.marketplace.client.model.CustomerInfo
import dev.ja.marketplace.client.model.CustomerType
import dev.ja.marketplace.client.model.LicensePeriod
import dev.ja.marketplace.client.model.PluginSale
import dev.ja.marketplace.client.model.PluginSaleItemType
import dev.ja.marketplace.client.model.SubscriptionPluginSaleItem
import org.javamoney.moneta.FastMoney

object SubscriptionTestData {
    fun customer(id: Int, type: CustomerType = CustomerType.Individual): CustomerInfo {
        return CustomerInfo(id, "DE", type, "Customer $id")
    }

    /**
     * A sale of a single license, which is valid for one month or one year from the start date.
     */
    fun sale(
        customer: CustomerInfo,
        licenseId: String,
        start: YearMonthDay,
        amountUSD: Int,
        period: LicensePeriod = LicensePeriod.Monthly,
        type: PluginSaleItemType = PluginSaleItemType.New,
    ): PluginSale {
        val end = when (period) {
            LicensePeriod.Annual -> start.add(1, 0, -1)
            else -> start.add(0, 1, -1)
        }
        val sale = SalesGenerator.createSale(
            type = period,
            saleDate = start,
            validity = start.rangeTo(end),
            customer = customer,
            amount = FastMoney.of(amountUSD, MarketplaceCurrencies.USD),
            saleType = type,
        )
        val item = sale.lineItems.single() as SubscriptionPluginSaleItem
        return sale.copy(lineItems = listOf(item.copy(licenseIds = listOf(licenseId))))
    }

    fun licenses(vararg sales: PluginSale): List<LicenseInfo> {
        return LicenseInfo.createFrom(sales.toList())
    }
}
