/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client

import dev.ja.marketplace.client.model.DownloadCountType
import dev.ja.marketplace.client.model.DownloadRequestDimension
import dev.ja.marketplace.client.model.IntelliJFamily
import dev.ja.marketplace.client.model.PerformanceRating
import dev.ja.marketplace.client.model.TeamCityRecipesFamily
import dev.ja.marketplace.client.model.PluginRatingCategory
import dev.ja.marketplace.client.model.PluginVerificationType
import dev.ja.marketplace.client.model.UnknownRatingCategory
import io.ktor.client.engine.mock.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

class MarketplaceClientRequestsTest {
    private val requests = CopyOnWriteArrayList<HttpRequestData>()

    private fun createClient(respond: (HttpRequestData) -> String): MarketplaceClient {
        val engine = MockEngine { request ->
            requests += request
            respond(respond(request), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        return CaffeineCacheMarketplaceClient(apiKey = API_KEY, httpClientBuilder = mockHttpClientBuilder(engine))
    }

    @Test
    suspend fun checkLicense() {
        // the documentation shows the dates as ISO dates and as arrays
        val client = createClient {
            """{"licenses": [
                {"type": "COMMERCIAL", "trial": false, "period": "ANNUAL", "startFrom": "2025-06-26", "validTill": [2026, 6, 25], "overuseTill": "2026-07-25T00:00:00Z", "ownerCode": 1234, "ownerType": "ORGANIZATION"},
                {"type": "PERSONAL", "trial": true}
            ]}"""
        }
        val result = client.checkLicense("PCODE", "customer@example.com")

        assertEquals("/api/marketplace/plugin/PCODE/license", requests.single().url.encodedPath)
        assertEquals("customer@example.com", requests.single().url.parameters["email"])
        val license = result.licenses.first()
        assertEquals(YearMonthDay(2025, 6, 26), license.startFrom)
        assertEquals(YearMonthDay(2026, 6, 25), license.validTill)
        assertEquals(YearMonthDay(2026, 7, 25), license.overuseTill)
        assertEquals("1234", license.ownerCode)
        assertEquals(true, result.licenses[1].isTrial)
        assertNull(result.licenses[1].validTill)
    }

    @Test
    suspend fun vendors() {
        val client = createClient {
            """[{"type": "Organization", "id": 1, "name": "Vendor", "isVerified": true, "description": "About",
                 "details": {"city": "City", "state": null}}]"""
        }
        val vendor = client.vendors().single()
        assertEquals("/api/users/me/vendors", requests.single().url.encodedPath)
        assertEquals("About", vendor.description)
        assertEquals("City", vendor.details?.city)
    }

    @Test
    suspend fun allPluginsRequestsAllPages() {
        val client = createClient { request ->
            val page = request.url.parameters["page"]!!.toInt()
            val count = if (page == 1) PLUGINS_PAGE_SIZE else 2
            (1..count).joinToString(",", "[", "]") { """{"id": ${page * 1000 + it}, "name": "Plugin", "link": "/plugin/$it", "xmlId": "id$it", "preview": "", "downloads": 0, "pricingModel": "FREE", "rating": 0.0, "hasSource": false}""" }
        }
        assertEquals(PLUGINS_PAGE_SIZE + 2, client.allPlugins("user").size)
        assertEquals(listOf("1", "2"), requests.map { it.url.parameters["page"] })
        assertEquals(setOf("$PLUGINS_PAGE_SIZE"), requests.map { it.url.parameters["size"] }.toSet())

        // the families are requested by their IDs
        requests.clear()
        client.plugins("user", listOf(IntelliJFamily, TeamCityRecipesFamily))
        assertEquals(listOf("intellij", "teamcity_recipes"), requests.single().url.parameters.getAll("family"))
    }

    @Test
    suspend fun downloadsUntilAnEndDate() {
        val client = createClient { """{"measure": "downloads", "filters": [], "dim1": "month", "data": {"dimension": "month", "serie": []}}""" }
        client.downloads(1, DownloadRequestDimension.Month, DownloadCountType.Downloads, YearMonthDay(2025, 1, 1), endDate = YearMonthDay(2025, 3, 31))
        assertEquals("2025-03-31", requests.single().url.parameters["endDate"])
        client.downloads(1, DownloadRequestDimension.Month, DownloadCountType.Downloads, YearMonthDay(2025, 1, 1))
        assertNull(requests.last().url.parameters["endDate"])
    }

    @Test
    suspend fun ratingByCategory() {
        val client = createClient { """{"PERFORMANCE": {"mean": 4.5, "votes": 12}, "NEW_CATEGORY": {"mean": 3.0, "votes": 1}}""" }
        val ratings = client.pluginRatingByCategory(1)
        assertEquals("/api/plugins/1/rating-granular", requests.single().url.encodedPath)
        assertEquals(12, ratings.getValue(PerformanceRating).votes)
        // a category, which the API added later
        assertEquals(3.0, ratings.getValue(UnknownRatingCategory("NEW_CATEGORY")).mean)
        assertEquals(PerformanceRating, PluginRatingCategory.findById("PERFORMANCE"))
    }

    @Test
    suspend fun verificationResults() {
        val client = createClient {
            """[{"resultId": 1, "updateId": 2, "verificationType": "INTELLIJ_COMPATIBILITY", "resultType": "PROBLEMS", "verificationVerdict": "2 problems",
                 "verificationDate": 1760000000000, "availableProduct": {"product": "IU", "type": "eap", "isRelease": false}}]"""
        }
        val result = client.verificationResults(2, PluginVerificationType.IdePerformance).single()
        assertEquals("/api/verifications/update/2", requests.single().url.encodedPath)
        assertEquals("IDE_PERFORMANCE", requests.single().url.parameters["verificationType"])
        assertEquals("PROBLEMS", result.resultType)
        assertEquals("IU", result.product?.product)
    }

    @Test
    suspend fun responsesAreCached() {
        val client = createClient { "[]" }
        repeat(2) {
            client.pluginReleaseDependencies(10)
            client.unsupportedReleaseProducts(10)
            client.pluginDevelopers(1)
            client.pluginFeatures(1)
            client.marketplacePrograms(1)
            client.userVendors("user")
        }
        assertEquals(6, requests.size, requests.joinToString { it.url.encodedPath })
    }

    @Test
    suspend fun concurrentRequestsShareOneFetch() = coroutineScope {
        val client = createClient { "[]" }
        (1..5).map { async { client.pluginFeatures(1) } }.awaitAll()
        assertEquals(1, requests.size)
    }

    @Test
    suspend fun reportIsDownloadedWithoutApiKey() {
        // a presigned address, its encoded parameters must be sent unchanged
        val reportUrl = "https://reports.example.com/report.csv?X-Amz-Credential=a%2Fb%2Bc&X-Amz-Signature=d%3D"
        val client = createClient { request ->
            when (request.url.host) {
                Marketplace.HOSTNAME -> reportUrl
                else -> "code,processed_date,customer_type,license_type,billing_period,sum_quantity,revenue_usd\n" +
                        "PCODE,2025-03-14,Organization,Commercial,Annual,2,199.80\n"
            }
        }
        val rows = client.salesAnalyticsReport(1)

        assertEquals(1, rows.size)
        assertEquals("Bearer $API_KEY", requests[0].headers[HttpHeaders.Authorization])
        assertEquals(reportUrl, requests[1].url.toString())
        assertNull(requests[1].headers[HttpHeaders.Authorization])
    }

    private companion object {
        const val API_KEY = "api-key"
    }
}
