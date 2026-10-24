/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client.persistentCache

import dev.ja.marketplace.client.YearMonthDayRange
import dev.ja.marketplace.client.fastMoneyOf
import dev.ja.marketplace.client.model.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.lang.reflect.Modifier
import java.nio.file.Path

/**
 * The stored classes in `CachedSalesModel.kt` mirror the API model by hand.
 * These tests fail if a property is added to the API model, but not to the stored classes.
 */
class CachedSalesModelCoverageTest {
    @TempDir
    lateinit var tempDir: Path

    /**
     * @param renamed Properties of the stored class, which have another name in the API class.
     * @param storedOnly Properties of the stored class without a property of the same name in the API class.
     * @param omitted Properties of the API class, which are intentionally not stored.
     */
    private class Mapping(
        val api: Class<*>,
        val stored: Class<*>,
        val renamed: Map<String, String> = emptyMap(),
        val storedOnly: Set<String> = emptySet(),
        val omitted: Set<String> = emptySet(),
    )

    private val saleItemRenames = mapOf(
        "discounts" to "discountDescriptions",
        "subscriptionStart" to "subscriptionDates",
        "subscriptionEnd" to "subscriptionDates",
    )

    private val mappings = listOf(
        // the currency is part of the amounts in the API model
        Mapping(PluginSale::class.java, CachedSale::class.java, storedOnly = setOf("currency")),
        Mapping(SubscriptionPluginSaleItem::class.java, CachedSaleItem::class.java, saleItemRenames),
        Mapping(
            PerpetualPluginSaleItem::class.java, CachedSaleItem::class.java, saleItemRenames,
            storedOnly = setOf("subscriptionStart", "subscriptionEnd"),
        ),
        Mapping(PluginSaleItemDiscount::class.java, CachedDiscount::class.java),
        Mapping(CustomerInfo::class.java, CachedCustomer::class.java),
        Mapping(ResellerInfo::class.java, CachedReseller::class.java),
        Mapping(PluginTrial::class.java, CachedTrial::class.java, mapOf("ref" to "referenceId")),
    )

    // the properties of the primary constructor, Kotlin data classes store them in fields
    private fun properties(type: Class<*>): Set<String> {
        return type.declaredFields
            .filter { !Modifier.isStatic(it.modifiers) && !it.isSynthetic }
            .mapTo(sortedSetOf()) { it.name }
    }

    @Test
    fun storedClassesCoverAllPropertiesOfTheApiModel() {
        for (mapping in mappings) {
            val apiProperties = properties(mapping.api)
            val storedProperties = properties(mapping.stored)
            assertNotEquals(emptySet<String>(), apiProperties, "No properties found for ${mapping.api.name}")

            val covered = (storedProperties - mapping.storedOnly).mapTo(sortedSetOf()) { mapping.renamed[it] ?: it } + mapping.omitted
            assertEquals(
                apiProperties, covered,
                "The properties of ${mapping.api.simpleName} don't match the stored class ${mapping.stored.simpleName}. " +
                        "Update CachedSalesModel.kt (stored class and conversion functions), increment the data version " +
                        "in HistoryKind and update the mapping in this test."
            )
        }
    }

    @Test
    fun fullyPopulatedSaleRoundTrip() {
        val date = day(2024, 2, 29)
        val sale = PluginSale(
            "ref-full",
            date,
            fastMoneyOf(123.45, "EUR"),
            fastMoneyOf(134.56, "USD"),
            LicensePeriod.Monthly,
            CustomerInfo(1234, "Austria", CustomerType.Individual, "Customer name"),
            ResellerInfo(567, "Reseller name", "Switzerland", ResellerType.Organization),
            listOf(
                SubscriptionPluginSaleItem(
                    PluginSaleItemType.Renew,
                    listOf("license-1", "license-2"),
                    fastMoneyOf(100.05, "EUR"),
                    fastMoneyOf(110.06, "USD"),
                    listOf(PluginSaleItemDiscount("Reseller discount", 5.5), PluginSaleItemDiscount("Other discount", 100.0)),
                    YearMonthDayRange(date, day(2024, 3, 28)),
                ),
                PerpetualPluginSaleItem(
                    PluginSaleItemType.New,
                    listOf("license-3"),
                    fastMoneyOf(23.4, "EUR"),
                    fastMoneyOf(24.5, "USD"),
                    listOf(PluginSaleItemDiscount("Discount", 1.25)),
                ),
            ),
        )
        val trial = PluginTrial("trial-full", date, CustomerInfo(1234, "Austria", CustomerType.Organization, "Company"))

        val store = PersistentCacheStore(tempDir)
        store.write("sales", HistoryKind.Sales.dataVersion, HistoryKind.Sales.serializer, CachedHistory(1, 2, 3, listOf(sale.toCached())))
        store.write("trials", HistoryKind.Trials.dataVersion, HistoryKind.Trials.serializer, CachedHistory(1, 2, 3, listOf(trial.toCached())))

        val storedSales = store.read("sales", HistoryKind.Sales.dataVersion, HistoryKind.Sales.serializer)!!.items
        assertEquals(listOf(sale), storedSales.map { it.toModel() })

        val storedTrials = store.read("trials", HistoryKind.Trials.dataVersion, HistoryKind.Trials.serializer)!!.items
        assertEquals(listOf(trial), storedTrials.map { it.toModel() })
    }
}
