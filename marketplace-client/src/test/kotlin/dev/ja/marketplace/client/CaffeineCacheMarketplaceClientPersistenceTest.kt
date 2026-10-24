/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client

import dev.ja.marketplace.client.persistentCache.PersistentCacheStore
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

class CaffeineCacheMarketplaceClientPersistenceTest {
    @TempDir
    lateinit var tempDir: Path

    private val salesRequests = CopyOnWriteArrayList<String>()
    private val trialRequests = CopyOnWriteArrayList<String>()

    // a sale and a trial on the first day of each month of the requested range, the plugin ID is part of the reference
    private fun createClient(store: PersistentCacheStore?): CaffeineCacheMarketplaceClient {
        val engine = MockEngine { request ->
            val begin = YearMonthDay.parse(request.url.parameters["beginDate"]!!)
            val end = YearMonthDay.parse(request.url.parameters["endDate"]!!)
            val plugin = request.url.segments[3]
            val dates = YearMonthDayRange(begin, end).days().filter { it.day == 1 }.toList()
            val body = when {
                request.url.encodedPath.endsWith("/sales-info") -> {
                    salesRequests += request.url.encodedPath
                    dates.joinToString(",", "[", "]") { saleJson("$plugin-${it.asIsoString}", it) }
                }

                else -> {
                    trialRequests += request.url.encodedPath
                    dates.joinToString(",", "[", "]") { trialJson("$plugin-${it.asIsoString}", it) }
                }
            }
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }

        val httpClient = HttpClient(engine) {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                })
            }
            install(DefaultRequest) {
                url("https://${Marketplace.HOSTNAME}")
            }
            expectSuccess = true
        }

        return CaffeineCacheMarketplaceClient(httpClient = httpClient, persistentCache = store)
    }

    private fun saleJson(ref: String, date: YearMonthDay): String = """
        {
          "ref": "$ref", "date": [${date.year}, ${date.month}, ${date.day}], "amount": 10.0, "amountUSD": 10.0, "currency": "USD", "period": "Annual",
          "customer": {"code": 1, "name": "Customer", "country": "Germany", "type": "Organization"},
          "lineItems": [{"type": "NEW", "licenseIds": ["license-$ref"], "subscriptionDates": null, "amount": 10.0, "amountUsd": 10.0, "discountDescriptions": []}]
        }
    """.trimIndent()

    private fun trialJson(ref: String, date: YearMonthDay): String = """
        {"ref": "$ref", "date": [${date.year}, ${date.month}, ${date.day}], "customer": {"code": 2, "country": "France", "type": "Personal"}}
    """.trimIndent()

    @Test
    fun withoutPersistentCache() = runBlocking {
        val client = createClient(null)
        val sales = client.salesInfo(1)
        assertTrue(sales.isNotEmpty())
        assertTrue(sales.all { it.ref.startsWith("1-") })
        assertEquals(emptyList<Path>(), tempDir.toFile().listFiles()!!.map { it.toPath() })
    }

    @Test
    fun restartUsesStoredData() = runBlocking {
        val store = PersistentCacheStore(tempDir).accountStore(Marketplace.HOSTNAME, "key")
        val client = createClient(store)
        val sales = client.licenseInfo(1)
        val trials = client.trialsInfo(1)
        assertTrue(salesRequests.isNotEmpty())
        assertTrue(trialRequests.isNotEmpty())

        val initialSalesRequests = salesRequests.size
        val initialTrialRequests = trialRequests.size
        salesRequests.clear()
        trialRequests.clear()
        val restarted = createClient(store)
        assertEquals(sales, restarted.licenseInfo(1))
        assertEquals(trials, restarted.trialsInfo(1))

        // only the recent days are fetched again in the background, not the complete history
        restarted.awaitPersistentCacheUpdates()
        assertTrue(salesRequests.isNotEmpty() && trialRequests.isNotEmpty())
        assertTrue(salesRequests.size < initialSalesRequests, "$salesRequests")
        assertTrue(trialRequests.size < initialTrialRequests, "$trialRequests")
    }

    @Test
    fun storedHistoryIsUpdatedAfterTheStartWithoutReports() = runBlocking {
        val store = PersistentCacheStore(tempDir).accountStore(Marketplace.HOSTNAME, "key")
        val client = createClient(store)
        client.salesInfo(1)
        client.trialsInfo(1)
        salesRequests.clear()
        trialRequests.clear()

        // plugin 2 has no stored data, it's only loaded when a report needs it
        val restarted = createClient(store)
        restarted.updateStoredHistory(listOf(1, 2))
        restarted.awaitPersistentCacheUpdates()
        assertTrue(salesRequests.isNotEmpty() && trialRequests.isNotEmpty())
        assertTrue((salesRequests + trialRequests).all { "/plugin/1/" in it }, "${salesRequests + trialRequests}")
    }

    @Test
    fun noUpdateAfterTheStartWithoutPersistentCache() = runBlocking {
        val client = createClient(null)
        client.updateStoredHistory(listOf(1))
        client.awaitPersistentCacheUpdates()
        assertEquals(emptyList<String>(), salesRequests + trialRequests)
    }

    @Test
    fun pluginsAndAccountsAreSeparated() = runBlocking {
        val root = PersistentCacheStore(tempDir)
        val first = createClient(root.accountStore(Marketplace.HOSTNAME, "key-1"))
        assertTrue(first.salesInfo(1).all { it.ref.startsWith("1-") })
        assertTrue(first.salesInfo(2).all { it.ref.startsWith("2-") })

        salesRequests.clear()
        val otherAccount = createClient(root.accountStore(Marketplace.HOSTNAME, "key-2"))
        assertTrue(otherAccount.salesInfo(1).isNotEmpty())
        assertTrue(salesRequests.isNotEmpty(), "data of another account must not be used")
    }

    @Test
    fun invalidateCacheFetchesCompleteHistory() = runBlocking {
        val store = PersistentCacheStore(tempDir).accountStore(Marketplace.HOSTNAME, "key")
        val client = createClient(store)
        client.salesInfo(1)
        val fullFetchRequests = salesRequests.size

        salesRequests.clear()
        client.invalidateCache()
        client.salesInfo(1)
        assertEquals(fullFetchRequests, salesRequests.size)

        // plugins, which weren't loaded before the invalidation, ignore the stored data, too
        client.trialsInfo(1)
        val fullTrialRequests = trialRequests.size
        trialRequests.clear()
        val restarted = createClient(store)
        restarted.invalidateCache()
        restarted.trialsInfo(1)
        assertEquals(fullTrialRequests, trialRequests.size)
    }
}
