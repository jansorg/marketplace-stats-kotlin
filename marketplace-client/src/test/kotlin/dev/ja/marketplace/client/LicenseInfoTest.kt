/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client

import dev.ja.marketplace.client.model.CustomerInfo
import dev.ja.marketplace.client.model.CustomerType
import dev.ja.marketplace.client.model.LicensePeriod
import dev.ja.marketplace.client.model.PluginSale
import dev.ja.marketplace.client.model.PluginSaleItemType
import dev.ja.marketplace.client.model.SubscriptionPluginSaleItem
import org.javamoney.moneta.FastMoney
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LicenseInfoTest {
    private val customer = CustomerInfo(1, "DE", CustomerType.Individual, "Customer")
    private val date = YearMonthDay(2025, 3, 1)

    private fun sale(total: Int, vararg items: Pair<Int, List<String>>): PluginSale {
        val usd = { amount: Int -> FastMoney.of(amount, "USD") }
        val lineItems = items.map { (amount, licenses) ->
            SubscriptionPluginSaleItem(PluginSaleItemType.New, licenses, usd(amount), usd(amount), emptyList(), date.rangeTo(date.add(1, 0, -1)))
        }
        return PluginSale("ref", date, usd(total), usd(total), LicensePeriod.Annual, customer, null, lineItems)
    }

    private fun amounts(sale: PluginSale): Map<String, FastMoney> {
        return LicenseInfo.createFrom(listOf(sale)).associate { it.id to FastMoney.from(it.amountUSD) }
    }

    @Test
    fun refundWithSeveralLineItems() {
        // each line item keeps its amount, the licenses add up to the refund
        val licenses = amounts(sale(-80, -50 to listOf("a"), -30 to listOf("b", "c")))
        assertEquals(mapOf("a" to FastMoney.of(-50, "USD"), "b" to FastMoney.of(-15, "USD"), "c" to FastMoney.of(-15, "USD")), licenses)
    }

    @Test
    fun totalBelowTheLineItems() {
        // e.g. a sale changed by JetBrains, the licenses add up to the total of the sale
        assertEquals(mapOf("a" to FastMoney.of(5, "USD"), "b" to FastMoney.of(5, "USD")), amounts(sale(10, 50 to listOf("a"), 50 to listOf("b"))))
        assertEquals(mapOf("a" to FastMoney.of(0, "USD"), "b" to FastMoney.of(0, "USD")), amounts(sale(0, 50 to listOf("a"), 50 to listOf("b"))))
    }
}
