/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client

import io.ktor.client.engine.mock.*
import io.ktor.http.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.minutes

class CaffeineCacheMarketplaceClientRefreshTest {
    // the plugin and the date range of each request of sales
    private val requests = CopyOnWriteArrayList<Pair<String, YearMonthDayRange>>()

    private fun createClient(strategy: CachingStrategy): CaffeineCacheMarketplaceClient {
        val engine = MockEngine { request ->
            val range = YearMonthDayRange(YearMonthDay.parse(request.url.parameters["beginDate"]!!), YearMonthDay.parse(request.url.parameters["endDate"]!!))
            val plugin = request.url.segments[3]
            requests += plugin to range
            val body = range.days().filter { it.day == 1 }.joinToString(",", "[", "]") { saleJson("$plugin-${it.asIsoString}", it) }
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        return CaffeineCacheMarketplaceClient(httpClientBuilder = mockHttpClientBuilder(engine), cachingStrategy = strategy)
    }

    private fun saleJson(ref: String, date: YearMonthDay): String = """
        {
          "ref": "$ref", "date": [${date.year}, ${date.month}, ${date.day}], "amount": 10.0, "amountUSD": 10.0, "currency": "USD", "period": "Annual",
          "customer": {"code": 1, "name": "Customer", "country": "Germany", "type": "Organization"},
          "lineItems": [{"type": "NEW", "licenseIds": ["license-$ref"], "subscriptionDates": null, "amount": 10.0, "amountUsd": 10.0, "discountDescriptions": []}]
        }
    """.trimIndent()

    @Test
    suspend fun refreshFetchesTheRecentDataOfThePlugin() {
        val client = createClient(CachingStrategy(autoRefreshInterval = 30.minutes))
        val licenses = client.licenseInfo(1)
        client.licenseInfo(2)
        assertTrue(requests.size > 2)

        requests.clear()
        client.refreshPlugin(1)
        // only the recent months of the plugin, which may still change, the older months are kept
        assertEquals(listOf("1"), requests.map { it.first }.distinct())
        val unstableStart = YearMonthDay.now().add(0, 0, -CachingStrategy.UNSTABLE_HISTORY_DAYS)
        assertTrue(requests.isNotEmpty() && requests.all { it.second.end >= unstableStart }, "$requests")

        // requests use the refreshed data without fetching it
        requests.clear()
        assertEquals(licenses.sales, client.licenseInfo(1).sales)
        assertEquals(emptyList<Pair<String, YearMonthDayRange>>(), requests)
    }

    @Test
    suspend fun nothingIsRefreshedWithoutTheAutomaticRefresh() {
        val client = createClient(CachingStrategy())
        client.licenseInfo(1)
        requests.clear()
        client.refreshPlugin(1)
        assertEquals(emptyList<Pair<String, YearMonthDayRange>>(), requests)
    }
}
