/*
 * Copyright (c) 2023 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.week

import dev.ja.marketplace.client.*
import dev.ja.marketplace.client.Currency
import dev.ja.marketplace.data.*
import java.math.BigDecimal
import java.util.*

class WeekTable(title: String = "This week") : SimpleDataTable(title, cssClass = "small table-striped"),
    MarketplaceDataSink {

    private data class WeekData(
        var sales: Amount,
        var downloads: Long
    )

    private val columnDay = DataTableColumn("day", null)
    private val columnSales = DataTableColumn("total", "Sales", "num")
    private val columnDownloads = DataTableColumn("total", "Downloads", "num")

    private val dateRange = YearMonthDayRange.currentWeek()
    private val data = TreeMap<YearMonthDay, WeekData>()

    override val columns: List<DataTableColumn> = listOf(columnDay, columnSales, columnDownloads)

    override fun init(data: PluginData) {
        dateRange.days().forEach { day ->
            val downloads = data.downloadsDaily.firstOrNull { it.day == day }?.downloads ?: 0
            this.data[day] = WeekData(BigDecimal.ZERO, downloads)
        }
    }

    override fun process(licenseInfo: LicenseInfo) {
        if (licenseInfo.sale.date in dateRange) {
            data.compute(licenseInfo.sale.date) { _, current ->
                current!!.also {
                    it.sales += licenseInfo.amountUSD
                }
            }
        }
    }

    override val sections: List<DataTableSection>
        get() {
            val now = YearMonthDay.now()
            val rows = data.entries.map { (date, weekData) ->
                SimpleDateTableRow(
                    mapOf(
                        columnDay to date,
                        columnSales to weekData.sales.withCurrency(Currency.USD),
                        columnDownloads to if (date < now) weekData.downloads.toBigInteger() else "—",
                    ),
                    cssClass = when {
                        date == now -> "today"
                        date > now -> "future"
                        else -> null
                    }
                )
            }

            return listOf(
                SimpleTableSection(
                    rows, footer = SimpleTableSection(
                        SimpleDateTableRow(
                            columnSales to data.values.sumOf { it.sales }.withCurrency(Currency.USD),
                            columnDownloads to data.values.sumOf { it.downloads },
                        )
                    )
                )
            )
        }
}