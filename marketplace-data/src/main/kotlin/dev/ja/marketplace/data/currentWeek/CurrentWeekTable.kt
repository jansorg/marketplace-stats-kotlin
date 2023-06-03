/*
 * Copyright (c) 2023 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.currentWeek

import dev.ja.marketplace.client.Amount
import dev.ja.marketplace.data.*
import dev.ja.marketplace.client.*
import dev.ja.marketplace.client.Currency
import java.math.BigDecimal
import java.util.*

class CurrentWeekTable : SimpleDataTable("This week", cssClass = "small table-striped"), MarketplaceDataSink {
    private val columnDay = DataTableColumn("day", null)
    private val columnTotal = DataTableColumn("total", "Sales", "num")

    private val dateRange = YearMonthDayRange.currentWeek()
    private val data = TreeMap<YearMonthDay, Amount>()

    override val columns: List<DataTableColumn> = listOf(columnDay, columnTotal)

    override fun init(data: PluginData) {
        dateRange.days().forEach {
            this.data[it] = BigDecimal.ZERO
        }
    }

    override fun process(licenseInfo: LicenseInfo) {
        if (licenseInfo.sale.date in dateRange) {
            data.compute(licenseInfo.sale.date) { _, current ->
                (current ?: BigDecimal.ZERO) + licenseInfo.amountUSD
            }
        }
    }

    override val sections: List<DataTableSection>
        get() {
            val now = YearMonthDay.now()
            val rows = data.entries.map { (date, totalAmount) ->
                SimpleDateTableRow(
                    mapOf(
                        columnDay to date,
                        columnTotal to totalAmount.withCurrency(Currency.USD)
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
                        SimpleDateTableRow(columnTotal to data.values.sumOf { it }.withCurrency(Currency.USD))
                    )
                )
            )
        }
}