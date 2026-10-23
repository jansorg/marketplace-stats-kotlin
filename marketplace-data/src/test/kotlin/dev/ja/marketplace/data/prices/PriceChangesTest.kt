/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.prices

import dev.ja.SalesGenerator
import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.client.currency.MarketplaceCurrencies
import dev.ja.marketplace.client.model.CustomerInfo
import dev.ja.marketplace.client.model.CustomerType
import dev.ja.marketplace.client.model.LicensePeriod
import dev.ja.marketplace.client.model.PluginSale
import dev.ja.marketplace.client.model.PluginSaleItemDiscount
import dev.ja.marketplace.client.model.PluginSaleItemType
import dev.ja.marketplace.client.model.SubscriptionPluginSaleItem
import org.javamoney.moneta.FastMoney
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PriceChangesTest {
    private val usCustomer = CustomerInfo(1, "United States", CustomerType.Individual, "Customer")
    private val usOrganization = CustomerInfo(2, "United States", CustomerType.Organization, "Organization")
    private val germanCustomer = CustomerInfo(3, "Germany", CustomerType.Individual, "Customer")

    private fun sale(
        date: YearMonthDay,
        price: Int,
        customer: CustomerInfo = usCustomer,
        type: PluginSaleItemType = PluginSaleItemType.New,
        discount: Boolean = false,
        period: LicensePeriod = LicensePeriod.Annual,
    ): PluginSale {
        val sale = SalesGenerator.createSale(
            type = period,
            saleDate = date,
            customer = customer,
            amount = FastMoney.of(price, MarketplaceCurrencies.USD),
            saleType = type,
        )
        if (!discount) {
            return sale
        }
        val item = sale.lineItems.single() as SubscriptionPluginSaleItem
        return sale.copy(lineItems = listOf(item.copy(discountDescriptions = listOf(PluginSaleItemDiscount("Promotion", 50.0)))))
    }

    @Test
    fun priceChanges() {
        val sales = listOf(
            sale(YearMonthDay(2024, 1, 10), 23),
            sale(YearMonthDay(2024, 2, 10), 23),
            // a single sale with a different amount, e.g. a correction of the taxes
            sale(YearMonthDay(2024, 3, 10), 24),
            sale(YearMonthDay(2024, 4, 10), 23),
            // ignored: discounted, renewal, monthly license, other country
            sale(YearMonthDay(2024, 5, 1), 11, discount = true),
            sale(YearMonthDay(2024, 5, 2), 15, type = PluginSaleItemType.Renew),
            sale(YearMonthDay(2024, 5, 3), 3, period = LicensePeriod.Monthly),
            sale(YearMonthDay(2024, 5, 4), 30, customer = germanCustomer),
            // the new price
            sale(YearMonthDay(2024, 6, 1), 39),
            sale(YearMonthDay(2024, 6, 5), 39),
            sale(YearMonthDay(2024, 7, 1), 39),
            // organizations have their own price
            sale(YearMonthDay(2024, 1, 15), 68, customer = usOrganization),
            sale(YearMonthDay(2024, 2, 15), 68, customer = usOrganization),
            sale(YearMonthDay(2024, 8, 14), 99, customer = usOrganization),
            sale(YearMonthDay(2024, 9, 1), 99, customer = usOrganization),
        )

        // the first sale of an organization at the new price is after the change of the individual price,
        // the last sale at the old price was before, so it's the same change
        val changes = PriceChanges.find(sales)
        assertEquals(listOf(YearMonthDay(2024, 6, 1)), changes.map { it.date })
        assertEquals(listOf("Price 39 USD · Org. 99 USD"), changes.map { it.label })
    }

    @Test
    fun separateChanges() {
        val sales = listOf(
            sale(YearMonthDay(2024, 1, 10), 23),
            sale(YearMonthDay(2024, 2, 10), 23),
            sale(YearMonthDay(2024, 6, 1), 39),
            sale(YearMonthDay(2024, 6, 5), 39),
            // organizations bought at the old price after the change of the individual price
            sale(YearMonthDay(2024, 1, 15), 68, customer = usOrganization),
            sale(YearMonthDay(2024, 7, 15), 68, customer = usOrganization),
            sale(YearMonthDay(2025, 3, 1), 99, customer = usOrganization),
            sale(YearMonthDay(2025, 4, 1), 99, customer = usOrganization),
        )
        val changes = PriceChanges.find(sales)
        assertEquals(listOf(YearMonthDay(2024, 6, 1), YearMonthDay(2025, 3, 1)), changes.map { it.date })
        assertEquals(listOf("Price 39 USD", "Org. 99 USD"), changes.map { it.label })
    }

    @Test
    fun noChangeWithoutAnEstablishedPrice() {
        assertEquals(emptyList<PriceChange>(), PriceChanges.find(listOf(sale(YearMonthDay(2024, 1, 10), 23), sale(YearMonthDay(2024, 2, 10), 39))))
    }
}
