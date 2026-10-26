/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client.persistentCache

import dev.ja.marketplace.client.model.*
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Values of the sales, which the Marketplace adds later, don't fail the sales, they're decoded as `Unknown`.
 */
class UnknownSaleValuesTest {
    @TempDir
    lateinit var tempDir: Path

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val salesJson = """
        [
          {
            "ref": "R1", "date": [2025, 3, 1], "amount": 10.0, "amountUSD": 10.0, "currency": "USD", "period": "Quarterly",
            "customer": {"code": 1, "name": "Customer", "country": "Germany", "type": "Student"},
            "reseller": {"code": 2, "name": "Reseller", "country": "Germany", "type": "Distributor"},
            "lineItems": [{"type": "UPGRADE", "licenseIds": ["L1"], "subscriptionDates": null, "amount": 10.0, "amountUsd": 10.0, "discountDescriptions": []}]
          },
          {
            "ref": "R2", "date": [2025, 3, 2], "amount": 10.0, "amountUSD": 10.0, "currency": "USD", "period": "Annual",
            "customer": {"code": 3, "name": "Customer", "country": "Germany", "type": "Personal"},
            "lineItems": [{"type": "NEW", "licenseIds": ["L2"], "subscriptionDates": null, "amount": 10.0, "amountUsd": 10.0, "discountDescriptions": []}]
          }
        ]
    """.trimIndent()

    @Test
    fun unknownValuesAreDecodedAsUnknown() {
        val sales = json.decodeFromString(ListSerializer(PluginSale.serializer()), salesJson)
        val sale = sales[0]
        assertSame(LicensePeriod.Unknown, sale.licensePeriod)
        assertSame(CustomerType.Unknown, sale.customer.type)
        assertSame(ResellerType.Unknown, sale.reseller?.type)
        assertSame(PluginSaleItemType.Unknown, sale.lineItems.single().type)
        // the known values of the other sale
        assertSame(LicensePeriod.Annual, sales[1].licensePeriod)
        assertSame(CustomerType.Individual, sales[1].customer.type)
        assertSame(PluginSaleItemType.New, sales[1].lineItems.single().type)
    }

    @Test
    fun unknownValuesAreStored() {
        val sales = json.decodeFromString(ListSerializer(PluginSale.serializer()), salesJson)
        val store = PersistentCacheStore(tempDir)
        val kind = HistoryKind.Sales
        store.write("sales", kind.dataVersion, kind.serializer, CachedHistory(1, 2, 3, sales.map(kind.toCached)))
        assertEquals(sales, store.read("sales", kind.dataVersion, kind.serializer)!!.items.map(kind.toModel))
    }

    @Test
    fun unknownPricingModel() {
        assertSame(PluginPricingModel.Unknown, json.decodeFromString(PluginPricingModel.serializer(), "\"SUBSCRIPTION_ONLY\""))
        assertSame(PluginPricingModel.Freemium, json.decodeFromString(PluginPricingModel.serializer(), "\"FREEMIUM\""))
        assertEquals("\"PAID\"", json.encodeToString(PluginPricingModel.serializer(), PluginPricingModel.Paid))
    }

    @Test
    fun extensibleTypesReturnTheSameInstances() {
        // known values are the predefined objects, unknown values are created once
        assertSame(PerformanceRating, PluginRatingCategory.findById("PERFORMANCE"))
        assertSame(PluginRatingCategory.findById("NEW_CATEGORY"), PluginRatingCategory.findById("NEW_CATEGORY"))
        assertSame(IntelliJFamily, ProductFamily.findById("intellij"))
        assertSame(ProductFamily.findById("new_family"), json.decodeFromString(ProductFamily.serializer(), "\"new_family\""))
    }
}
