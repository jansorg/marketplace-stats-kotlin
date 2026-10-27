/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.privingOverview

import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.client.model.LicensingType
import dev.ja.marketplace.client.model.MarketplacePluginInfo
import dev.ja.marketplace.data.RenderedDataTable
import dev.ja.marketplace.data.SimpleDateTableRow
import dev.ja.marketplace.mcp.McpTestData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import javax.money.MonetaryAmount

class PricingOverviewTableTest {
    private suspend fun render(licensing: LicensingType, withRenewalPrices: Boolean = true): RenderedDataTable {
        val info = MarketplacePluginInfo("PCODE", "Plugin", emptyList(), licensingType = licensing)
        val data = McpTestData.pluginData(withPricing = true, marketplacePluginInfo = info, withRenewalPrices = withRenewalPrices)
        val table = PricingOverviewTable()
        table.init(data)
        return table.renderTable()
    }

    private fun RenderedDataTable.usRow(): SimpleDateTableRow {
        return sections.flatMap { it.rows }.map { it as SimpleDateTableRow }.single { row -> row.values.values.contains("United States") }
    }

    @Test
    suspend fun annualPricesOfSubscriptions() {
        val table = render(LicensingType.Subscription)
        assertEquals(listOf("pricing-country", "pricing-first-year", "pricing-second-year", "pricing-third-year"),
            table.sections.first().columns!!.map { it.id }.take(4))
        assertEquals("50", firstAmount(table.usRow().values.entries.single { it.key.id == "pricing-first-year" }.value))
    }

    @Test
    suspend fun annualPricesWithoutContinuityDiscount() {
        val table = render(LicensingType.Subscription, withRenewalPrices = false)
        val columns = table.sections.first().columns!!
        assertEquals(listOf("pricing-country", "pricing-first-year", "pricing-first-year-commercial"), columns.map { it.id })
        assertEquals(listOf(null, "Annual", "Annual"), columns.map { it.title })
        // the country and a column of each customer type in the header
        assertEquals(listOf(1, 1, 1), table.columns.map { it.columnSpan })
        assertEquals("50", firstAmount(table.usRow().values.entries.single { it.key.id == "pricing-first-year" }.value))
    }

    @Test
    fun continuityDiscountNote() {
        fun info(hasDiscount: Boolean?, disabledAt: YearMonthDay? = null) = MarketplacePluginInfo(
            "PCODE", "Plugin", emptyList(), hasContinuityDiscount = hasDiscount, continuityDiscountDisabledAt = disabledAt,
        )
        assertNull(PricingOverviewTable.continuityDiscountNote(null))
        assertNull(PricingOverviewTable.continuityDiscountNote(info(null)))
        assertNull(PricingOverviewTable.continuityDiscountNote(info(true)))
        assertEquals("The plugin has no continuity discount: renewals pay the price of the 1st year.", PricingOverviewTable.continuityDiscountNote(info(false)))
        assertTrue(PricingOverviewTable.continuityDiscountNote(info(false, YearMonthDay(2025, 3, 1)))!!.startsWith("The continuity discount was disabled on 2025-03-01: licenses bought before keep it"))
        // the API may return the date without the flag, a date in the future still has the discount
        val today = YearMonthDay(2026, 1, 1)
        assertTrue(PricingOverviewTable.continuityDiscountNote(info(null, YearMonthDay(2025, 3, 1)), today)!!.startsWith("The continuity discount was disabled"))
        assertNull(PricingOverviewTable.continuityDiscountNote(info(null, YearMonthDay(2026, 3, 1)), today))
        assertEquals(false, PricingOverviewTable.hasContinuityDiscount(info(null, YearMonthDay(2025, 3, 1)), today))
        assertEquals(null, PricingOverviewTable.hasContinuityDiscount(info(null, YearMonthDay(2026, 3, 1)), today))
        assertEquals(true, PricingOverviewTable.hasContinuityDiscount(info(true), today))
    }

    @Test
    suspend fun perpetualPrices() {
        val table = render(LicensingType.Perpetual)
        assertEquals(listOf("pricing-country", "pricing-perpetual", "pricing-perpetual-commercial"), table.sections.first().columns!!.map { it.id })
        assertEquals("150", firstAmount(table.usRow().values.entries.single { it.key.id == "pricing-perpetual" }.value))
    }

    private fun firstAmount(value: Any?): String {
        return (value as List<*>).first().let { (it as MonetaryAmount).number.numberValue(BigDecimal::class.java).stripTrailingZeros().toPlainString() }
    }
}
