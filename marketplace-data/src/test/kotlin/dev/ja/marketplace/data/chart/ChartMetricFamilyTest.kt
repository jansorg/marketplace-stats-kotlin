/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.chart

import dev.ja.marketplace.client.PluginReleaseId
import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.client.model.CustomerType
import dev.ja.marketplace.client.model.DailyDownload
import dev.ja.marketplace.exchangeRate.EcbExchangeRateData
import dev.ja.marketplace.exchangeRate.ExchangeRates
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class ChartMetricFamilyTest {
    private val exchangeRates = ExchangeRates("USD", EcbExchangeRateData())
    private val today = YearMonthDay(2025, 9, 30)

    /** The downloads of the release 1001 in IntelliJ IDEA and of the release 1002 in all products. */
    private val source = object : ChartDownloadsSource {
        val requests = mutableListOf<Pair<PluginReleaseId?, String?>>()
        val starts = mutableListOf<YearMonthDay>()
        val byMonths = mutableListOf<Boolean>()

        override suspend fun releases() = listOf(ChartParameterValue("1002", "1.1"), ChartParameterValue("1001", "1.0"))

        override suspend fun products() = listOf(ChartParameterValue("IIU", "IntelliJ IDEA"), ChartParameterValue("PCP", "PyCharm"))

        override suspend fun downloads(release: PluginReleaseId?, product: String?, start: YearMonthDay, byMonth: Boolean): List<DailyDownload> {
            requests += release to product
            starts += start
            byMonths += byMonth
            return when (release to product) {
                1001 to "IIU" -> listOf(DailyDownload(YearMonthDay(2025, 9, 1), 3), DailyDownload(YearMonthDay(2025, 9, 2), 4))
                1002 to null -> listOf(DailyDownload(YearMonthDay(2025, 3, 1), 5), DailyDownload(YearMonthDay(2025, 8, 15), 10))
                else -> emptyList()
            }.filter { it.day >= start }
        }
    }

    private val downloads = listOf(DailyDownload(YearMonthDay(2025, 1, 10), 100))

    @Test
    fun idsOfTheMetrics() {
        val metric = ChartMetric.byId("release-downloads.1001.IIU")!!
        assertSame(ChartMetricFamily.ReleaseDownloads, metric.family)
        assertEquals(listOf("1001", "IIU"), metric.parameterValues)
        assertSame(metric, ChartMetricFamily.ReleaseDownloads.metric(listOf("1001", "IIU")))
        // the product is optional
        assertEquals(listOf("1001", null), ChartMetric.byId("release-downloads.1001")!!.parameterValues)
        assertEquals("product-downloads.IIU", ChartMetric.byId("product-downloads.IIU")!!.id)

        // the IDs of the metrics, which existed before the families
        assertSame(ChartMetric.customerTypeShare(CustomerType.Individual), ChartMetric.byId("customer-type-share.Individual"))
        assertSame(ChartMetric.discount("Summer"), ChartMetric.byId(ChartMetric.discount("Summer").id))

        // the release is a number, the product is required for the downloads in a product
        assertNull(ChartMetric.byId("release-downloads.x"))
        assertNull(ChartMetric.byId("release-downloads"))
        assertNull(ChartMetric.byId("release-downloads.1.IIU.x"))
        assertNull(ChartMetric.byId("product-downloads."))
        assertFalse(metric in ChartMetric.all)
    }

    @Test
    suspend fun downloadsOfAReleaseInAProduct() {
        val values = ChartMetricValues.freePlugin(downloads, exchangeRates, today, source)
        val metric = ChartMetric.byId("release-downloads.1001.IIU")!!
        assertTrue(values.isAvailable(metric))
        // the downloads are loaded when they're requested
        assertNull(values.series(metric, ChartWindow.CalendarMonth))
        values.prepare(listOf(metric, ChartMetric.Downloads))
        values.prepare(listOf(metric))
        assertEquals(listOf<Pair<PluginReleaseId?, String?>>(1001 to "IIU"), source.requests)

        val series = values.series(metric, ChartWindow.CalendarMonth)!!
        assertEquals(BigDecimal(7), series.values[values.days.indexOf(today)])
        // the title contains the labels of the values
        assertEquals("Downloads of 1.0 in IntelliJ IDEA", metric.title)
        assertEquals(ChartUnit.Count, metric.unit)
        assertTrue(metric.usesWindow)
    }

    @Test
    suspend fun downloadsOfAReleaseInAllProducts() {
        val values = ChartMetricValues.freePlugin(downloads, exchangeRates, today, source)
        val metric = ChartMetric.byId("release-downloads.1002")!!
        values.prepare(listOf(metric))
        assertEquals(BigDecimal(15), values.series(metric, ChartWindow.CalendarYear)!!.values[values.days.indexOf(today)])
        assertEquals("Downloads of 1.1 in all products", metric.title)
    }

    @Test
    suspend fun downloadsFromTheStartOfTheShownDates() {
        val values = ChartMetricValues.freePlugin(downloads, exchangeRates, today, source)
        val metric = ChartMetric.byId("release-downloads.1002")!!
        values.prepare(listOf(metric), YearMonthDay(2025, 7, 1))
        assertEquals(listOf(YearMonthDay(2025, 7, 1)), source.starts)

        // the windows, which start before the loaded downloads, have no value
        val months = values.series(metric, ChartWindow.CalendarMonth)!!.values
        assertEquals(BigDecimal(10), months[values.days.indexOf(YearMonthDay(2025, 8, 31))])
        assertNull(months[values.days.indexOf(YearMonthDay(2025, 6, 30))])
        assertNull(values.series(metric, ChartWindow.CalendarYear)!!.values[values.days.indexOf(today)])

        // a later start uses the loaded downloads, an earlier one loads them again
        values.prepare(listOf(metric), YearMonthDay(2025, 8, 1))
        values.prepare(listOf(metric), YearMonthDay(2025, 1, 1))
        assertEquals(listOf(YearMonthDay(2025, 7, 1), YearMonthDay(2025, 1, 1)), source.starts)
        assertEquals(BigDecimal(15), values.series(metric, ChartWindow.CalendarYear)!!.values[values.days.indexOf(today)])
    }

    @Test
    fun requestsOfTheHalvesOfTheMonths() {
        // the halves start at the 1st and the 16th of the months, the current half until today
        assertEquals(
            listOf(
                YearMonthDay(2026, 8, 16) to YearMonthDay(2026, 8, 31),
                YearMonthDay(2026, 9, 1) to YearMonthDay(2026, 9, 15),
                YearMonthDay(2026, 9, 16) to YearMonthDay(2026, 9, 30),
                YearMonthDay(2026, 10, 1) to YearMonthDay(2026, 10, 15),
                YearMonthDay(2026, 10, 16) to null,
            ),
            halfMonthChunks(YearMonthDay(2026, 8, 20), YearMonthDay(2026, 10, 28)),
        )
        // February, and today is the last day of a half
        assertEquals(
            listOf(YearMonthDay(2028, 2, 1) to YearMonthDay(2028, 2, 15), YearMonthDay(2028, 2, 16) to null),
            halfMonthChunks(YearMonthDay(2028, 2, 3), YearMonthDay(2028, 2, 29)),
        )
        assertEquals(listOf(YearMonthDay(2026, 10, 1) to null), halfMonthChunks(YearMonthDay(2026, 10, 1), YearMonthDay(2026, 10, 15)))
    }

    @Test
    suspend fun downloadsByMonthAreLoadedAgainByDay() {
        val values = ChartMetricValues.freePlugin(downloads, exchangeRates, today, source)
        val metric = ChartMetric.byId("release-downloads.1002")!!
        values.prepare(listOf(metric), byMonth = true)
        // the downloads by month are enough again, the downloads by day are loaded once
        values.prepare(listOf(metric), byMonth = true)
        values.prepare(listOf(metric), byMonth = false)
        values.prepare(listOf(metric), byMonth = true)
        values.prepare(listOf(metric), byMonth = false)
        assertEquals(listOf(true, false), source.byMonths)
    }

    @Test
    fun requestsOfTheYears() {
        assertEquals(
            listOf(YearMonthDay(2024, 1, 1) to YearMonthDay(2024, 12, 31), YearMonthDay(2025, 1, 1) to YearMonthDay(2025, 12, 31), YearMonthDay(2026, 1, 1) to null),
            yearChunks(YearMonthDay(2024, 3, 5), YearMonthDay(2026, 10, 28)),
        )
    }

    @Test
    suspend fun valuesOfTheParameters() {
        val values = ChartMetricValues.freePlugin(downloads, exchangeRates, today, source)
        assertEquals(setOf(ChartMetricFamily.ReleaseDownloads, ChartMetricFamily.ProductDownloads), values.families)
        assertEquals(listOf("1.1", "1.0"), values.parameterValues(ChartMetricFamily.ReleaseDownloads, 0).map { it.label })
        assertEquals(listOf("IIU", "PCP"), values.parameterValues(ChartMetricFamily.ProductDownloads, 0).map { it.value })
        // a free plugin has no shares of the customer types
        assertEquals(emptyList<ChartParameterValue>(), values.parameterValues(ChartMetricFamily.CustomerTypeShare, 0))
        assertFalse(values.isAvailable(ChartMetric.customerTypeShare(CustomerType.Individual)))

        // without a source, there are no downloads of releases and products
        val withoutSource = ChartMetricValues.freePlugin(downloads, exchangeRates, today)
        assertFalse(withoutSource.isAvailable(ChartMetric.byId("release-downloads.1002")!!))
        assertEquals(emptySet<ChartMetricFamily>(), withoutSource.families)
    }

    @Test
    suspend fun valuesOfThePaidMetrics() {
        val values = ChartMetricValues.create(emptyList(), emptyList(), emptyList(), downloads, exchangeRates, today)
        // without discounts, there's no family of the discounts
        assertEquals(setOf(ChartMetricFamily.CustomerTypeShare), values.families)
        assertEquals(setOf("Individual", "Organization"), values.parameterValues(ChartMetricFamily.CustomerTypeShare, 0).map { it.value }.toSet())
        assertTrue(values.isAvailable(ChartMetric.customerTypeShare(CustomerType.Organization)))
    }
}
