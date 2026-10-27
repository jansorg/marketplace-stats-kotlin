/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.offers

import dev.ja.marketplace.client.MarketplaceClient
import dev.ja.marketplace.client.PluginId
import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.client.currency.MarketplaceCurrencies
import dev.ja.marketplace.client.model.*
import dev.ja.marketplace.data.ChartMetricName
import dev.ja.marketplace.data.DataTableColumn
import dev.ja.marketplace.data.PluginData
import dev.ja.marketplace.data.SimpleDateTableRow
import dev.ja.marketplace.data.chart.ChartMetric
import dev.ja.marketplace.mcp.McpTestData
import org.javamoney.moneta.FastMoney
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.math.BigDecimal
import java.math.BigInteger
import javax.money.MonetaryAmount

class OffersTableTest {
    private val today = YearMonthDay(2025, 10, 1)
    private val pluginId = McpTestData.PLUGIN_ID

    private val running = PluginOffer(1, "Running", pluginId, YearMonthDay(2025, 1, 1), listOf("Annual"), discountPercent = 25)
    private val ended = PluginOffer(2, "Ended", pluginId, YearMonthDay(2024, 1, 1), listOf("Annual"), discountPercent = 50, endDate = YearMonthDay(2024, 12, 31))
    private val usedUp = PluginOffer(3, "Used up", pluginId, YearMonthDay(2025, 1, 1), listOf("Annual"), freePeriodInMonths = 3)

    private val coupons = mapOf(
        running.id to listOf(PluginOfferCoupon("A", isActive = true, isSent = true), PluginOfferCoupon("B", isActive = false, isSent = false)),
        ended.id to listOf(PluginOfferCoupon("C", isActive = true, isSent = false)),
        usedUp.id to listOf(PluginOfferCoupon("D", isActive = false, isSent = true)),
    )

    private val sales = listOf(
        sale("s1", YearMonthDay(2025, 2, 1), 75, PluginSaleItemDiscount("\"Running\" offer", 25.0)),
        sale("s2", YearMonthDay(2024, 6, 1), 50, PluginSaleItemDiscount("\"Ended\" offer", 50.0)),
        // a renewal with the discount of the ended offer, in the last 90 days
        sale("s5", YearMonthDay(2025, 9, 15), 50, PluginSaleItemDiscount("\"Ended\" offer", 50.0)),
        sale("s3", YearMonthDay(2025, 3, 1), 0, PluginSaleItemDiscount("\"Offer of JetBrains\" offer", 100.0)),
        sale("s4", YearMonthDay(2025, 9, 1), 60, PluginSaleItemDiscount("40% continuity discount", 40.0)),
        sale("s6", YearMonthDay(2024, 9, 1), 80, PluginSaleItemDiscount("20% continuity discount", 20.0)),
        // used in the last 90 days, it's active
        sale("s7", YearMonthDay(2025, 9, 10), 90, PluginSaleItemDiscount("Loyalty discount", 10.0)),
        // specials of the Marketplace
        sale("s8", YearMonthDay(2025, 9, 11), 0, PluginSaleItemDiscount("\"Student discount for Test Plugin\" offer", 100.0)),
        sale("s9", YearMonthDay(2025, 9, 12), 90, PluginSaleItemDiscount("Subscription volume discount", 10.0)),
        sale("s10", YearMonthDay(2025, 9, 13), 0, PluginSaleItemDiscount("\"Student discount for Test Plugin\" offer", 100.0)),
        sale("s11", YearMonthDay(2025, 9, 14), 50, PluginSaleItemDiscount("50% non-profit discount on commercial plugin Test Plugin", 50.0)),
    )

    @Test
    suspend fun activeOffers() {
        val (table, rows) = render(OffersTableKind.Offers, "Active")
        // the active offers and the other offers and discounts used recently, the latest use first
        assertEquals(listOf("Loyalty discount", "Running"), rows.map { it.values[column(table, "offer-name")].toString() })
        val row = rows[1]
        assertEquals("Running", row.values[column(table, "offer-name")].toString())
        assertEquals("25 %", row.values[column(table, "offer-discount")])
        assertEquals(BigInteger.ONE, row.values[column(table, "offer-licenses")])
        assertEquals("2025-01-01 —", row.values[column(table, "offer-dates")])
        assertAmount(75, row.values[column(table, "offer-paid")])
        assertAmount(25, row.values[column(table, "offer-discount-amount")])
        assertEquals(ChartMetric.discount("Running").id, chartMetric(table, row))
        assertEquals("Discount: Running", (row.values[column(table, "offer-name")] as ChartMetricName).title)

        val discountRow = rows[0]
        assertEquals("10 %", discountRow.values[column(table, "offer-discount")])
        assertAmount(10, discountRow.values[column(table, "offer-discount-amount")])
    }

    @Test
    suspend fun marketplaceDiscounts() {
        val (table, rows) = render(OffersTableKind.Marketplace)
        // the continuity discounts by their percent, then the specials by their use, with their percents and without
        // the name of the plugin
        assertEquals(
            listOf(
                "20% continuity discount", "40% continuity discount", "Student discount (100 %)", "50% non-profit discount",
                "Subscription volume discount (10 %)",
            ),
            rows.map { it.values[column(table, "offer-name")].toString() },
        )
        assertAmount(20, rows[0].values[column(table, "offer-discount-amount")])
        assertAmount(40, rows[1].values[column(table, "offer-discount-amount")])
        assertEquals(BigInteger.TWO, rows[2].values[column(table, "offer-licenses")])
        assertAmount(50, rows[3].values[column(table, "offer-discount-amount")])
        assertAmount(10, rows[4].values[column(table, "offer-discount-amount")])
        // the buttons of the charts of the discounts use their names in the sales, free discounts have none
        assertEquals(ChartMetric.discount("20% continuity discount").id, chartMetric(table, rows[0]))
        assertNull(chartMetric(table, rows[2]))
        assertEquals(ChartMetric.discount("50% non-profit discount on commercial plugin Test Plugin").id, chartMetric(table, rows[3]))
        assertEquals(ChartMetric.discount("Subscription volume discount").id, chartMetric(table, rows[4]))
        assertNull(column(table, "offer-discount-amount").chartMetric)
        assertEquals(listOf("offer-name", "offer-licenses", "offer-sales", "offer-customers", "offer-paid", "offer-discount-amount"), table.columns.map { it.id })
    }

    @Test
    suspend fun inactiveOffers() {
        val (table, rows) = render(OffersTableKind.Offers, "Inactive")
        // the inactive offers and the other offers and discounts, which weren't used recently, the latest use first
        assertEquals(listOf("Ended", "Offer of JetBrains", "Used up"), rows.map { it.values[column(table, "offer-name")].toString() })
        // an ended offer is inactive, also if it was used recently
        assertEquals(BigInteger.TWO, rows[0].values[column(table, "offer-licenses")])
        assertAmount(100, rows[0].values[column(table, "offer-discount-amount")])
        assertEquals("2024-01-01 — 2024-12-31", rows[0].values[column(table, "offer-dates")])
        // unused offers are last
        assertEquals("3 months free", rows[2].values[column(table, "offer-discount")])
        assertEquals(BigInteger.ZERO, rows[2].values[column(table, "offer-licenses")])

        val otherRow = rows[1]
        assertEquals(BigInteger.ONE, otherRow.values[column(table, "offer-licenses")])
        assertTrue(table.columns.none { it.id == "offer-free" })
        // a free license has no lost amount
        assertNull(otherRow.values[column(table, "offer-discount-amount")])
        assertTrue(table.columns.none { it.id == "offer-coupons" || it.id == "offer-periods" })
    }

    @Test
    suspend fun sectionsOfTheActiveAndInactiveOffers() {
        val data: PluginData = McpTestData.pluginData(sales = sales)
        val table = OffersTable(OffersTableKind.Offers, OfferSource.of(offersClient()), today)
        table.init(data)
        data.getLicenses()!!.forEach { table.process(it) }
        assertEquals(listOf("Active", "Inactive"), table.renderTable().sections.map { it.title })
        assertEquals("Offers", table.title)

        // a single section has the title of the table
        val runningOnly = object : OfferSource {
            override suspend fun offers(pluginId: PluginId): List<PluginOffer> = listOf(running)
            override suspend fun offerCoupons(pluginId: PluginId, offer: PluginOfferId): List<PluginOfferCoupon> = coupons.getValue(offer)
        }
        val onlyActive = OffersTable(OffersTableKind.Offers, runningOnly, today)
        val runningData: PluginData = McpTestData.pluginData(sales = sales.filter { it.ref == "s1" })
        onlyActive.init(runningData)
        runningData.getLicenses()!!.forEach { onlyActive.process(it) }
        assertEquals(1, onlyActive.renderTable().sections.size)
        assertEquals("Active", onlyActive.title)
    }

    private suspend fun render(kind: OffersTableKind, section: String? = null): Pair<OffersTable, List<SimpleDateTableRow>> {
        val data: PluginData = McpTestData.pluginData(sales = sales)
        val table = OffersTable(kind, OfferSource.of(offersClient()), today)
        table.init(data)
        data.getLicenses()!!.forEach { table.process(it) }
        val sections = table.renderTable().sections
        val rows = if (section == null) sections.single() else sections.single { it.title == section }
        return table to rows.rows.map { it as SimpleDateTableRow }
    }

    private fun offersClient(): MarketplaceClient {
        return Proxy.newProxyInstance(javaClass.classLoader, arrayOf(MarketplaceClient::class.java)) { _, method, args ->
            when (method.name) {
                "offers" -> listOf(running, ended, usedUp)
                "offerCoupons" -> coupons[args[1] as Int]
                else -> throw UnsupportedOperationException(method.name)
            }
        } as MarketplaceClient
    }

    private fun column(table: OffersTable, id: String): DataTableColumn = table.columns.single { it.id == id }

    /** @return The metric of the chart button of the name of a row. */
    private fun chartMetric(table: OffersTable, row: SimpleDateTableRow): String? {
        return (row.values[column(table, "offer-name")] as? ChartMetricName)?.metric
    }

    private fun sale(ref: String, date: YearMonthDay, amountUSD: Int, discount: PluginSaleItemDiscount): PluginSale {
        val amount = FastMoney.of(amountUSD, MarketplaceCurrencies.USD)
        val lineItem = SubscriptionPluginSaleItem(
            PluginSaleItemType.New, listOf("license-$ref"), amount, amount, listOf(discount), date.rangeTo(date.add(1, 0, -1)),
        )
        val customer = CustomerInfo(10, "Germany", CustomerType.Individual)
        return PluginSale(ref, date, amount, amount, LicensePeriod.Annual, customer, null, listOf(lineItem))
    }

    private fun assertAmount(expected: Int, actual: Any?) {
        val amount = actual as MonetaryAmount
        assertEquals(0, BigDecimal(expected).compareTo(amount.number.numberValue(BigDecimal::class.java)), "expected $expected, was $amount")
    }
}
