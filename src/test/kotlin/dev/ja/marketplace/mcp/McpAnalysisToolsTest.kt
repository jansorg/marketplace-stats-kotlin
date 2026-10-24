/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.mcp

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal

class McpAnalysisToolsTest {
    private val tools = MarketplaceMcpTools(McpTestData.provider)

    @Test
    suspend fun trials() {
        val result = tools.call("list_trials", arguments())
        assertEquals(60, result["conversionDays"]!!.jsonPrimitive.int)

        val summary = result["summary"]!!.jsonObject
        assertEquals(2, summary["trials"]!!.jsonPrimitive.int)
        assertEquals(1, summary["converted"]!!.jsonPrimitive.int)
        assertEquals(0, summary["pending"]!!.jsonPrimitive.int)
        assertEquals(BigDecimal("50.00"), percent(summary["conversionRate"]!!))

        val trials = result["trialList"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("trial-1", "trial-2"), trials.map { it["refNum"]!!.jsonPrimitive.content })
        assertEquals("converted", trials[0]["status"]!!.jsonPrimitive.content)
        assertEquals("ref-1", trials[0]["purchaseRefNum"]!!.jsonPrimitive.content)
        assertEquals(9, trials[0]["daysToPurchase"]!!.jsonPrimitive.int)
        assertEquals("notConverted", trials[1]["status"]!!.jsonPrimitive.content)
        assertNull(trials[1]["purchaseDate"])
        assertEquals(2, result["totalCount"]!!.jsonPrimitive.int)

        assertEquals(listOf("2024-03", "2025-01"), result["byMonth"]!!.jsonArray.map { it.jsonObject["month"]!!.jsonPrimitive.content })
    }

    @Test
    suspend fun trialsWithFilters() {
        val byCountry = tools.call("list_trials", arguments { put("country", "fr"); put("summaryOnly", true) })
        assertEquals(1, byCountry["summary"]!!.jsonObject["trials"]!!.jsonPrimitive.int)
        assertNull(byCountry["trialList"])

        val withoutCountry = tools.call("list_trials", arguments { put("country", "") })
        assertEquals(0, withoutCountry["summary"]!!.jsonObject["trials"]!!.jsonPrimitive.int)

        val byDate = tools.call("list_trials", arguments { put("startDate", "2025-01-01") })
        assertEquals(listOf("trial-2"), byDate["trialList"]!!.jsonArray.map { it.jsonObject["refNum"]!!.jsonPrimitive.content })

        assertThrows<IllegalArgumentException> { tools.call("list_trials", arguments { put("customerType", "Robot") }) }
    }

    @Test
    suspend fun downloadsByProduct() {
        val result = tools.call("get_downloads", arguments { put("startDate", "2025-01-01") })
        assertEquals("product", result["groupBy"]!!.jsonPrimitive.content)
        assertEquals(36, result["totalDownloads"]!!.jsonPrimitive.int)

        val groups = result["groups"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("IIU", "PCP", "unknown"), groups.map { it["productCode"]!!.jsonPrimitive.content })
        assertEquals(30, groups[0]["total"]!!.jsonPrimitive.int)
        // compact series: the values of the groups are in the order of the dates
        assertEquals(listOf("2025-08-01", "2025-09-01"), result["dates"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf(10, 20), groups[0]["values"]!!.jsonArray.map { it.jsonPrimitive.int })
        assertEquals(listOf(5, 0), groups[1]["values"]!!.jsonArray.map { it.jsonPrimitive.int })
        assertNull(groups[0]["pluginVersion"])

        val filtered = tools.call("get_downloads", arguments { put("startDate", "2025-09-01") })
        assertEquals(21, filtered["totalDownloads"]!!.jsonPrimitive.int)
    }

    @Test
    suspend fun downloadsOfMonthsStartingInTheMiddleOfAMonth() {
        // the month of the start date is included, also if the range starts after its first day
        val result = tools.call("get_downloads", arguments { put("startDate", "2025-08-15"); put("endDate", "2025-08-20") })
        assertEquals(15, result["totalDownloads"]!!.jsonPrimitive.int)
    }

    @Test
    suspend fun downloadsByPluginVersion() {
        val result = tools.call("get_downloads", arguments { put("groupBy", "pluginVersion"); put("startDate", "2025-01-01") })
        val groups = result["groups"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("2.0.0", "2.1.0"), groups.map { it["pluginVersion"]!!.jsonPrimitive.content })
        assertEquals(listOf(26, 9), groups.map { it["total"]!!.jsonPrimitive.int })

        val pyCharm = tools.call("get_downloads", arguments { put("groupBy", "pluginVersion"); put("productCode", "pcp"); put("startDate", "2025-01-01") })
        assertEquals(5, pyCharm["totalDownloads"]!!.jsonPrimitive.int)
    }

    @Test
    suspend fun downloadsByProductAndPluginVersion() {
        val result = tools.call("get_downloads", arguments { put("groupBy", "productAndPluginVersion"); put("startDate", "2025-01-01") })
        val groups = result["groups"]!!.jsonArray.map { it.jsonObject }
        assertEquals(
            listOf("IIU 2", "PCP 3", "IIU 3", "unknown null"),
            groups.map { "${it["productCode"]!!.jsonPrimitive.content} ${it["releaseId"]!!.jsonPrimitive.contentOrNull}" },
        )
        assertEquals(36, result["totalDownloads"]!!.jsonPrimitive.int)

        assertThrows<IllegalArgumentException> { tools.call("get_downloads", arguments { put("groupBy", "country") }) }
        assertThrows<IllegalArgumentException> { tools.call("get_downloads", arguments { put("granularity", "year") }) }
    }

    @Test
    suspend fun licenseCohorts() {
        val result = tools.call("get_license_cohorts", arguments())
        val cohorts = result["cohorts"]!!.jsonArray.map { it.jsonObject }
        assertEquals(
            listOf("annual 2024-03", "annual 2024-11", "monthly 2025-02"),
            cohorts.map { "${it["licensePeriod"]!!.jsonPrimitive.content} ${it["month"]!!.jsonPrimitive.content}" },
        )

        val renewal = cohorts[0]["renewals"]!!.jsonArray.single().jsonObject
        assertEquals(1, renewal["due"]!!.jsonPrimitive.int)
        assertEquals(0, renewal["renewed"]!!.jsonPrimitive.int)
        assertEquals(BigDecimal("0.00"), percent(renewal["renewalRate"]!!))

        val monthly = tools.call("get_license_cohorts", arguments { put("licensePeriod", "monthly") })
        assertEquals(1, monthly["cohorts"]!!.jsonArray.size)

        val range = tools.call("get_license_cohorts", arguments { put("startDate", "2024-11-15"); put("endDate", "2024-11-15") })
        assertEquals(1, range["cohorts"]!!.jsonArray.size)

        assertThrows<IllegalArgumentException> { tools.call("get_license_cohorts", arguments { put("licensePeriod", "Perpetual") }) }
    }

    @Test
    suspend fun reviews() {
        val result = tools.call("list_reviews", arguments())
        assertEquals(3, result["reviewCount"]!!.jsonPrimitive.int)

        val months = result["byMonth"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("2025-01", "2025-06"), months.map { it["month"]!!.jsonPrimitive.content })
        assertEquals(BigDecimal("4.00"), months[0]["averageRating"]!!.jsonPrimitive.content.toBigDecimal())

        val review = result["reviews"]!!.jsonArray.first().jsonObject
        assertEquals("2025-01-10", review["date"]!!.jsonPrimitive.content)
        assertEquals("Review 1", review["text"]!!.jsonPrimitive.content)
        assertEquals("Author 1", review["author"]!!.jsonPrimitive.content)

        val withoutText = tools.call("list_reviews", arguments { put("includeText", false); put("startDate", "2025-06-01") })
        assertEquals(1, withoutText["reviewCount"]!!.jsonPrimitive.int)
        assertNull(withoutText["reviews"]!!.jsonArray.single().jsonObject["text"])
    }

    @Test
    suspend fun pageViews() {
        val result = tools.call("get_page_views", arguments { put("timeFrame", "LastMonth") })
        assertEquals("lastMonth", result["timeFrame"]!!.jsonPrimitive.content)
        assertFalse(result["isReliable"]!!.jsonPrimitive.boolean)
        assertEquals("2025-01-01", result["firstDate"]!!.jsonPrimitive.content)
        assertEquals(7, result["totalPageViews"]!!.jsonPrimitive.int)
        assertEquals("United States", result["countries"]!!.jsonArray.first().jsonObject["country"]!!.jsonPrimitive.content)
        assertEquals("(direct)", result["sources"]!!.jsonArray.first().jsonObject["source"]!!.jsonPrimitive.content)

        assertThrows<IllegalArgumentException> { tools.call("get_page_views", arguments { put("timeFrame", "lastWeek") }) }
    }

    @Test
    suspend fun licenseTransfers() {
        // the licenses of the test data have a single customer
        val result = tools.call("list_license_transfers", arguments())
        assertEquals(0, result["transferCount"]!!.jsonPrimitive.int)
        assertEquals(0, result["byCustomerTypes"]!!.jsonObject.size)
    }

    private fun arguments(builder: JsonObjectBuilder.() -> Unit = {}): JsonObject {
        return buildJsonObject {
            put("pluginId", McpTestData.PLUGIN_ID)
            builder()
        }
    }

    private fun percent(value: JsonElement): BigDecimal {
        return value.jsonPrimitive.content.toBigDecimal()
    }
}
