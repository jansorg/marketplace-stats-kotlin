/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client

import dev.ja.marketplace.client.model.DownloadCountType
import dev.ja.marketplace.client.model.DownloadRequestDimension
import dev.ja.marketplace.client.persistentCache.PersistentCacheStore
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The parts of the downloads of long time ranges are stored one by one, so that overlapping time ranges use them again.
 */
class StoredDownloadPartsTest {
    @TempDir
    lateinit var tempDir: Path

    /** The requested ranges of the days, e.g. `2026-08-03..2026-08-09`. */
    private val requests = CopyOnWriteArrayList<String>()

    private fun createClient(): CaffeineCacheMarketplaceClient {
        val engine = MockEngine { request ->
            val start = request.url.parameters["startDate"]
            requests += "$start..${request.url.parameters["endDate"]}"
            respond(
                """{"measure": "downloads-count", "filters": [], "dim1": "day", "dim2": "product_code", "data": [
                    {"dimension": "IIU", "dimensionComment": "IntelliJ IDEA", "serie": [{"name": "$start", "value": 1}]}]}""",
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val store = PersistentCacheStore(tempDir).accountStore(Marketplace.HOSTNAME, "key")
        return CaffeineCacheMarketplaceClient(httpClientBuilder = mockHttpClientBuilder(engine), persistentCache = store)
    }

    private suspend fun MarketplaceClient.byProduct(start: YearMonthDay, end: YearMonthDay): List<String> {
        return groupedDownloads(1, DownloadRequestDimension.Day, DownloadRequestDimension.ProductCode, DownloadCountType.Downloads, start, endDate = end)
            .groups.single().serie.map { it.name }
    }

    @Test
    suspend fun overlappingTimeRangesUseTheStoredWeeks() {
        createClient().use { client ->
            // the weeks of August 2025, from Monday to Sunday
            assertEquals(listOf("2025-08-04", "2025-08-11"), client.byProduct(YearMonthDay(2025, 8, 4), YearMonthDay(2025, 8, 17)))
            assertEquals(listOf("2025-08-04..2025-08-10", "2025-08-11..2025-08-17"), requests.sorted())

            // the week of August 11 is stored, only the next week is requested
            requests.clear()
            assertEquals(listOf("2025-08-11", "2025-08-18"), client.byProduct(YearMonthDay(2025, 8, 11), YearMonthDay(2025, 8, 24)))
            assertEquals(listOf("2025-08-18..2025-08-24"), requests)
        }

        // after a restart, the stored weeks are used again
        requests.clear()
        createClient().use { client ->
            assertEquals(listOf("2025-08-04", "2025-08-11", "2025-08-18"), client.byProduct(YearMonthDay(2025, 8, 4), YearMonthDay(2025, 8, 24)))
        }
        assertEquals(emptyList<String>(), requests)
    }
}
