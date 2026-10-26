/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.privingOverview

import dev.ja.marketplace.client.model.LicensingType
import dev.ja.marketplace.client.model.MarketplacePluginInfo
import dev.ja.marketplace.data.RenderedDataTable
import dev.ja.marketplace.data.SimpleDateTableRow
import dev.ja.marketplace.mcp.McpTestData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import javax.money.MonetaryAmount

class PricingOverviewTableTest {
    private suspend fun render(licensing: LicensingType): RenderedDataTable {
        val info = MarketplacePluginInfo("PCODE", "Plugin", emptyList(), licensingType = licensing)
        val data = McpTestData.pluginData(withPricing = true, marketplacePluginInfo = info)
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
    suspend fun perpetualPrices() {
        val table = render(LicensingType.Perpetual)
        assertEquals(listOf("pricing-country", "pricing-perpetual", "pricing-perpetual-commercial"), table.sections.first().columns!!.map { it.id })
        assertEquals("150", firstAmount(table.usRow().values.entries.single { it.key.id == "pricing-perpetual" }.value))
    }

    private fun firstAmount(value: Any?): String {
        return (value as List<*>).first().let { (it as MonetaryAmount).number.numberValue(BigDecimal::class.java).stripTrailingZeros().toPlainString() }
    }
}
