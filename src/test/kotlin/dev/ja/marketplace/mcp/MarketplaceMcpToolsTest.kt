/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.mcp

import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.services.Country
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal

class MarketplaceMcpToolsTest {
    private val tools = MarketplaceMcpTools(McpTestData.provider)

    @Test
    fun toolList() {
        assertEquals(
            listOf(
                "list_plugins",
                "get_yearly_summary",
                "get_monthly_overview",
                "get_daily_summary",
                "get_sales_and_trials_breakdown",
                "get_top_countries",
                "get_mrr_movements",
                "get_revenue_retention",
                "get_customer_cohorts",
                "get_lifetime_value",
                "get_annual_renewal_rates",
                "get_monthly_renewal_rates",
                "get_average_revenue_per_customer",
                "list_sales",
                "list_customers",
                "list_licenses",
                "list_customer_licenses",
                "get_pricing",
                "get_price_changes",
                "list_plugin_releases",
                "list_offers",
                "list_jetbrains_product_releases",
                "get_jetbrains_product_release",
                "list_jetbrains_products",
                "get_exchange_rates",
                "refresh_data",
                "list_trials",
                "get_downloads",
                "get_license_cohorts",
                "list_reviews",
                "get_page_views",
                "list_license_transfers",
                "create_chart",
            ),
            tools.tools.map { it.name }
        )

        // the tools of the tables of the overview page
        for (tool in tools.tools.subList(1, 13)) {
            assertEquals(listOf("pluginId"), tool.required, tool.name)
            assertEquals(setOf("pluginId", "startDate", "endDate", "currency"), tool.properties.keys - "sortBy", tool.name)
        }
    }

    @Test
    suspend fun plugins() {
        val result = tools.call("list_plugins", JsonObject(emptyMap()))
        assertEquals("user-1", result["account"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        val plugins = result["plugins"]!!.jsonArray
        assertEquals(1, plugins.size)
        assertEquals(McpTestData.PLUGIN_ID, plugins[0].jsonObject["id"]!!.jsonPrimitive.int)
        assertTrue(plugins[0].jsonObject["isPaidOrFreemium"]!!.jsonPrimitive.boolean)
    }

    @Test
    suspend fun invalidArguments() {
        assertThrows<IllegalArgumentException> { tools.call("get_top_countries", JsonObject(emptyMap())) }
        assertThrows<IllegalArgumentException> { tools.call("get_top_countries", arguments(pluginId = 1)) }
        assertThrows<IllegalArgumentException> {
            tools.call("get_top_countries", arguments(startDate = "2024-13-01"))
        }
        assertThrows<IllegalArgumentException> { tools.call("unknown_tool", JsonObject(emptyMap())) }
    }

    @Test
    suspend fun salesAndTrialsBreakdownWithoutDateRange() {
        val result = tools.call("get_sales_and_trials_breakdown", arguments())
        assertEquals("USD", result["currency"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, result["startDate"])
        assertEquals(BigDecimal("410.00"), salesTotal(result))
    }

    @Test
    suspend fun salesAndTrialsBreakdownWithDateRange() {
        val result = tools.call("get_sales_and_trials_breakdown", arguments(startDate = "2024-01-01", endDate = "2024-12-31"))
        assertEquals("2024-01-01", result["startDate"]!!.jsonPrimitive.content)
        assertEquals(BigDecimal("400.00"), salesTotal(result))

        // the enum values are lower camel case
        val rows = result["sections"]!!.jsonArray[0].jsonObject["rows"]!!.jsonArray
        assertTrue(rows.any { row -> row.jsonArray.map { it.jsonPrimitive.content }.containsAll(listOf("annual", "organization")) }, rows.toString())
    }

    @Test
    suspend fun monthlyOverviewFiltersRows() {
        val result = tools.call("get_monthly_overview", arguments(startDate = "2024-10-15", endDate = "2024-11-30"))
        val sections = result["sections"]!!.jsonArray
        assertEquals(1, sections.size)

        val section = sections[0].jsonObject
        assertEquals("2024", section["title"]!!.jsonPrimitive.content)
        val months = section["rows"]!!.jsonArray.map { it.jsonArray[0].jsonPrimitive.content }
        assertEquals(listOf("2024-11", "2024-10"), months)
        // the footer summarizes the whole year, it's not returned for a part of the year
        assertNull(section["footer"])
    }

    @Test
    suspend fun monthlyOverviewOfWholeYear() {
        val result = tools.call("get_monthly_overview", arguments(startDate = "2024-01-01", endDate = "2024-12-31"))
        val section = result["sections"]!!.jsonArray.single().jsonObject
        assertEquals(12, section["rows"]!!.jsonArray.size)
        assertNotNull(section["footer"])
    }

    @Test
    suspend fun yearlySummary() {
        val all = tools.call("get_yearly_summary", arguments())
        val years = section(all)["rows"]!!.jsonArray.map { it.jsonArray[0].jsonPrimitive.int }
        assertTrue(years.containsAll(listOf(2024, 2025)), years.toString())
        assertNotNull(section(all)["footer"])

        val filtered = tools.call("get_yearly_summary", arguments(startDate = "2024-06-01", endDate = "2024-06-30"))
        assertEquals(listOf(2024), section(filtered)["rows"]!!.jsonArray.map { it.jsonArray[0].jsonPrimitive.int })
        assertNull(section(filtered)["footer"])
    }

    @Test
    suspend fun dailySummary() {
        val result = tools.call("get_daily_summary", arguments(startDate = "2024-03-01", endDate = "2024-03-10"))
        val rows = section(result)["rows"]!!.jsonArray
        assertEquals(10, rows.size)
        assertEquals("2024-03-01", rows.first().jsonArray[0].jsonPrimitive.content)
        assertEquals("2024-03-10", rows.last().jsonArray[0].jsonPrimitive.content)

        // sales of 2024-03-10
        assertEquals(BigDecimal("100.00"), rows.last().jsonArray[1].jsonPrimitive.content.toBigDecimal())
        assertEquals("USD", result["columns"]!!.jsonArray[1].jsonObject["unit"]!!.jsonPrimitive.content)
    }

    @Test
    suspend fun dailySummaryReturnsTheEffectiveDateRange() {
        // the end is limited to today
        val result = tools.call("get_daily_summary", arguments(startDate = "2024-03-01", endDate = "2999-12-31"))
        assertEquals("2024-03-01", result["startDate"]!!.jsonPrimitive.content)
        assertEquals(YearMonthDay.now().asIsoString, result["endDate"]!!.jsonPrimitive.content)
        assertEquals(YearMonthDay.now().asIsoString, section(result)["rows"]!!.jsonArray.last().jsonArray[0].jsonPrimitive.content)
    }

    @Test
    suspend fun salesAndTrialsBreakdown() {
        val result = tools.call("get_sales_and_trials_breakdown", arguments(startDate = "2025-01-01", endDate = "2025-12-31"))
        val sections = result["sections"]!!.jsonArray
        val salesFooter = sections[0].jsonObject["footer"]!!.jsonArray[0].jsonArray
        assertEquals(BigDecimal("10.00"), salesFooter.last().jsonPrimitive.content.toBigDecimal())

        val trialRows = sections[1].jsonObject["rows"]!!.jsonArray
        assertEquals(listOf("France"), trialRows.map { it.jsonArray[0].jsonPrimitive.content })
    }

    @Test
    suspend fun trialCountriesWithDateRange() {
        val all = tools.call("get_top_countries", arguments { put("sortBy", "trials") })
        assertEquals(setOf("Germany", "France"), section(all)["rows"]!!.jsonArray.map { it.jsonArray[0].jsonPrimitive.content }.toSet())

        val filtered = tools.call("get_top_countries", arguments(startDate = "2024-01-01", endDate = "2024-12-31") { put("sortBy", "trials") })
        val rows = section(filtered)["rows"]!!.jsonArray
        assertEquals(listOf("Germany"), rows.map { it.jsonArray[0].jsonPrimitive.content })
        // the trial was converted by the sale of the same customer
        assertEquals(BigDecimal("100.00"), rows.single().jsonArray[3].jsonPrimitive.content.toBigDecimal())
    }

    @Test
    suspend fun unknownArgumentsAreRejected() {
        val error = assertThrows<IllegalArgumentException> {
            tools.call("get_yearly_summary", buildJsonObject { put("pluginId", McpTestData.PLUGIN_ID); put("from", "2024-01-01") })
        }
        assertTrue(error.message!!.contains("startDate"), error.message)
    }

    @Test
    suspend fun tableColumnsHaveUniqueKeysAndUnits() {
        val result = tools.call("get_monthly_overview", arguments(startDate = "2024-01-01", endDate = "2024-12-31"))
        val columns = result["columns"]!!.jsonArray.map { it.jsonObject }
        val keys = columns.map { it["key"]!!.jsonPrimitive.content }
        assertEquals(keys.size, keys.toSet().size, keys.toString())
        assertTrue(keys.containsAll(listOf("totalSales", "invoice", "mrr", "arr")), keys.toString())
        assertEquals("USD", columns.first { it["key"]!!.jsonPrimitive.content == "totalSales" }["unit"]!!.jsonPrimitive.content)
        // the columns are shared by the sections of the years
        assertNull(section(result)["columns"])
    }

    @Test
    suspend fun subscriptionTables() {
        for (name in listOf("get_mrr_movements", "get_revenue_retention", "get_customer_cohorts", "get_lifetime_value",
            "get_annual_renewal_rates", "get_monthly_renewal_rates", "get_average_revenue_per_customer")) {
            val result = tools.call(name, arguments())
            assertNotNull(result["sections"], name)
        }

        val filtered = tools.call("get_mrr_movements", arguments(startDate = "2024-11-01", endDate = "2024-11-30"))
        assertEquals(listOf("2024-11"), section(filtered)["rows"]!!.jsonArray.map { it.jsonArray[0].jsonPrimitive.content })
    }

    private fun arguments(
        pluginId: Int = McpTestData.PLUGIN_ID,
        startDate: String? = null,
        endDate: String? = null,
        builder: JsonObjectBuilder.() -> Unit = {},
    ): JsonObject {
        return buildJsonObject {
            put("pluginId", pluginId)
            startDate?.let { put("startDate", it) }
            endDate?.let { put("endDate", it) }
            builder()
        }
    }

    @Test
    suspend fun topCountries() {
        val result = tools.call("get_top_countries", arguments())
        val countries = section(result)["rows"]!!.jsonArray.map { it.jsonArray[0].jsonPrimitive.content }
        // sorted by sales, the trial of France has no sale
        assertEquals(Country.UNITED_STATES, countries.first())
        assertThrows<IllegalArgumentException> { tools.call("get_top_countries", arguments { put("sortBy", "downloads") }) }
    }

    private fun section(result: JsonObject): JsonObject {
        return result["sections"]!!.jsonArray.first().jsonObject
    }

    private fun salesTotal(result: JsonObject): BigDecimal {
        val footer = section(result)["footer"]!!.jsonArray.single().jsonArray
        return footer.last().jsonPrimitive.content.toBigDecimal()
    }
}
