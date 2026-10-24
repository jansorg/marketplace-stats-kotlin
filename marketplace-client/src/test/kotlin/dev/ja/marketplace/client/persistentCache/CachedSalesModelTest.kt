/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client.persistentCache

import dev.ja.marketplace.client.model.*
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class CachedSalesModelTest {
    @TempDir
    lateinit var tempDir: Path

    private val apiJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    // sales in the format of the Marketplace API
    private val salesJson = """
        [
          {
            "ref": "ref-1", "date": [2024, 6, 12], "amount": 3.90, "amountUSD": 4.19, "currency": "EUR", "period": "Monthly",
            "customer": {"code": 42, "name": "", "country": "Germany", "type": "Personal"},
            "reseller": null,
            "lineItems": [
              {
                "type": "RENEW", "licenseIds": ["abcdef"],
                "subscriptionDates": {"start": [2024, 6, 12], "end": [2024, 7, 11]},
                "amount": 3.90, "amountUsd": 4.19, "discountDescriptions": []
              }
            ]
          },
          {
            "ref": "ref-2", "date": [2024, 6, 13], "amount": 1234.56789, "amountUSD": 1234.56789, "currency": "USD", "period": "Annual",
            "customer": {"code": 43, "name": "Company Inc.", "country": "United States", "type": "Organization"},
            "reseller": {"code": 7, "name": "Reseller Ltd.", "country": "Japan", "type": "Reseller"},
            "lineItems": [
              {
                "type": "NEW", "licenseIds": ["l1", "l2", "l3"],
                "subscriptionDates": {"start": [2024, 6, 13], "end": [2025, 6, 12]},
                "amount": 1000.0, "amountUsd": 1000.0,
                "discountDescriptions": [{"description": "Reseller discount", "percent": 5.0}, {"description": "Other discount"}]
              },
              {
                "type": "RENEW", "licenseIds": ["l4"],
                "subscriptionDates": {"start": [2024, 6, 13], "end": [2025, 6, 12]},
                "amount": 234.56789, "amountUsd": 234.56789, "discountDescriptions": [{"description": "Free", "percent": 100.0}]
              }
            ]
          },
          {
            "ref": "ref-3", "date": [2020, 2, 29], "amount": -12000, "amountUSD": -80.12, "currency": "JPY", "period": "Perpetual",
            "customer": {"code": 44, "name": "Person", "country": "Japan", "type": "Personal"},
            "reseller": {"code": 8, "name": "Org", "country": "Japan", "type": "Organization"},
            "lineItems": [
              {"type": "NEW", "licenseIds": [], "subscriptionDates": null, "amount": -12000, "amountUsd": -80.12, "discountDescriptions": []}
            ]
          }
        ]
    """.trimIndent()

    @Test
    fun salesRoundTrip() {
        val sales = apiJson.decodeFromString(ListSerializer(PluginSale.serializer()), salesJson)
        assertEquals(3, sales.size)

        val store = PersistentCacheStore(tempDir)
        val kind = HistoryKind.Sales
        val history = CachedHistory(1, 2, 3, sales.map(kind.toCached))
        store.write("sales", kind.dataVersion, kind.serializer, history)

        val stored = store.read("sales", kind.dataVersion, kind.serializer)!!
        assertEquals(1, stored.fetchedUntil)
        assertEquals(2, stored.lastFetchMillis)
        assertEquals(3, stored.lastFullFetchMillis)
        assertEquals(sales, stored.items.map(kind.toModel))
    }

    @Test
    fun trialsRoundTrip() {
        val trials = listOf(
            testTrial("trial-1", day(2023, 1, 1)),
            PluginTrial("trial-2", day(2024, 12, 31), CustomerInfo(1, "", CustomerType.Organization, "Name")),
        )

        val store = PersistentCacheStore(tempDir)
        val kind = HistoryKind.Trials
        store.write("trials", kind.dataVersion, kind.serializer, CachedHistory(0, 0, 0, trials.map(kind.toCached)))

        assertEquals(trials, store.read("trials", kind.dataVersion, kind.serializer)!!.items.map(kind.toModel))
    }

    @Test
    fun emptyHistoryRoundTrip() {
        val store = PersistentCacheStore(tempDir)
        val kind = HistoryKind.Sales
        store.write("sales", kind.dataVersion, kind.serializer, CachedHistory(10, 20, 30, emptyList()))

        val stored = store.read("sales", kind.dataVersion, kind.serializer)!!
        assertEquals(10, stored.fetchedUntil)
        assertEquals(emptyList<CachedSale>(), stored.items)
    }

    @Test
    fun dates() {
        val date = day(2019, 6, 25)
        assertEquals(date, cachedDate(date.toCached()))
    }
}
