/*
 * Copyright (c) 2023 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.downloads

import dev.ja.marketplace.client.MonthlyDownload
import dev.ja.marketplace.data.*

class DownloadsTable : SimpleDataTable("", "downloads"), MarketplaceDataSink {
    private lateinit var data: List<MonthlyDownload>

    private val columnMonth = DataTableColumn("month", null)
    private val columnDownloads = DataTableColumn("downloads", "Downloads", "num")

    override val columns: List<DataTableColumn> = listOf(columnMonth, columnDownloads)

    override fun createSections(): List<DataTableSection> {
        return listOf(
            SimpleTableSection(
                rows = data.map {
                    SimpleDateTableRow(
                        columnMonth to it.firstOfMonth,
                        columnDownloads to it.downloads.toBigInteger(),
                    )
                },
                footer = SimpleRowGroup(
                    SimpleDateTableRow(columnDownloads to data.sumOf { it.downloads }.toBigInteger())
                )
            )
        )
    }

    override fun init(data: PluginData) {
        this.data = data.downloadsMonthly
    }

    override fun process(licenseInfo: LicenseInfo) {
        // ignored
    }
}