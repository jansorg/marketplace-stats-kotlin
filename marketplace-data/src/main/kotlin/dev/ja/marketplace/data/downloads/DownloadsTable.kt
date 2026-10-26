/*
 * Copyright (c) 2023-2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.downloads

import dev.ja.marketplace.client.LicenseInfo
import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.client.model.MonthlyDownload
import dev.ja.marketplace.data.*
import dev.ja.marketplace.data.chart.ChartMetric

class DownloadsTable : SimpleDataTable("", "downloads"), MarketplaceDataSink {
    private lateinit var data: List<MonthlyDownload>

    private val columnMonth = DataTableColumn("month", null)
    private val columnDownloads = DataTableColumn(
        "downloads", "Downloads", "num", chartMeasure = ChartMeasure.Flow,
        chartMetric = ChartMetric.Downloads.id,
    )

    override val isChartable: Boolean = true
    override val chartPeriod: ChartPeriod = ChartPeriod.Month

    override val columns: List<DataTableColumn> = listOf(columnMonth, columnDownloads)

    override suspend fun init(data: PluginData) {
        super.init(data)

        this.data = data.getDownloadsMonthly()
        this.pluginStart = data.getFirstActivityDate()
    }

    /** The first day with data of the plugin, the downloads of the first month only cover a part of it. */
    private var pluginStart: YearMonthDay? = null

    override suspend fun createSections(): List<DataTableSection> {
        return listOf(
            SimpleTableSection(
                rows = data.map {
                    val now = YearMonthDay.now()
                    val isCurrentMonth = it.firstOfMonth.year == now.year && it.firstOfMonth.month == now.month
                    val isPartialMonth = isPartialPeriod(it.firstOfMonth, pluginStart)
                    SimpleDateTableRow(
                        values = mapOf(
                            columnMonth to it.firstOfMonth,
                            columnDownloads to incompleteIf(it.downloads.toBigInteger(), isCurrentMonth || isPartialMonth),
                        ),
                        tooltips = mapOf(columnMonth to pluginStart?.let(::partialPeriodTooltip).takeIf { isPartialMonth }),
                    )
                },
                footer = SimpleRowGroup(
                    SimpleDateTableRow(columnDownloads to data.sumOf { it.downloads }.toBigInteger())
                )
            )
        )
    }

    override suspend fun process(licenseInfo: LicenseInfo) {
        // ignored
    }
}