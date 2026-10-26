/*
 * Copyright (c) 2023-2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data

import dev.ja.marketplace.client.currency.MarketplaceCurrencies
import dev.ja.marketplace.client.currency.WithAmounts
import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.exchangeRate.ExchangeRates
import java.util.concurrent.atomic.AtomicReference
import javax.money.MonetaryAmount

enum class AriaSortOrder(val attributeValue: String) {
    Ascending("ascending"),
    Descending("descending"),
}

data class RenderedDataTable(
    val table: DataTable,
    val sections: List<DataTableSection>,
) : DataTable by table {
    val isEmpty: Boolean
        get() {
            return sections.isEmpty() || sections.all { it.rows.isEmpty() }
        }
}

/**
 * Tabular data calculated based on the Marketplace data.
 */
interface DataTable : MarketplaceDataSink {
    val id: String?
    val title: String?
    val columns: List<DataTableColumn>
    val cssClass: String?
    val header: DataRowGroup?

    val alwaysShowMainColumns: Boolean get() = false

    val isLimitedRendering: Boolean get() = false

    /**
     * If set, only this number of rows of the first section is shown, until the rows are expanded in the browser.
     * All rows are rendered, e.g. for the charts, see `js/collapsibleTables.js`.
     */
    val collapsedRowCount: Int? get() = null

    /**
     * If `true`, the numeric columns of the table can be shown in a chart.
     * The first column of each row must contain its date, e.g. a [dev.ja.marketplace.client.YearMonthDay].
     */
    val isChartable: Boolean get() = false

    /**
     * The period of the rows of a chartable table.
     */
    val chartPeriod: ChartPeriod get() = ChartPeriod.Day

    suspend fun renderTable(): RenderedDataTable
}

interface DataTableRow {
    fun getValue(column: DataTableColumn): Any?
    fun getSortValue(column: DataTableColumn): Long? = null
    fun getTooltip(column: DataTableColumn): String? = null

    /**
     * @return The values of the row in a column with [DataTableColumn.rowHistoryPeriod] over time, e.g. the share of a
     *  customer type in each month, by the first day of each period. Values are cell values like [getValue].
     */
    fun getChartHistory(column: DataTableColumn): Map<YearMonthDay, Any?>? = null
    val cssClass: String?
    val htmlId: String?
}

interface DataRowGroup {
    val title: String?
    val rows: List<DataTableRow>
}

interface DataTableSection : DataRowGroup {
    val columns: List<DataTableColumn>?
    val header: DataRowGroup?
    val footer: DataRowGroup?
    val cssClass: String?
}

abstract class SimpleDataTable(
    override val title: String,
    override val id: String? = null,
    override val cssClass: String? = null,
    override val header: DataRowGroup? = null,
) : DataTable {
    private val cachedSections = AtomicReference<List<DataTableSection>>(null)

    protected lateinit var exchangeRates: ExchangeRates

    protected abstract suspend fun createSections(): List<DataTableSection>

    override suspend fun init(data: PluginData) {
        this.exchangeRates = data.exchangeRates
    }

    override suspend fun renderTable(): RenderedDataTable {
        if (cachedSections.get() == null) {
            cachedSections.set(createSections())
        }
        return RenderedDataTable(this, cachedSections.get())
    }

    protected fun WithAmounts.renderAmount(date: YearMonthDay): MonetaryAmount {
        return when (exchangeRates.targetCurrency) {
            MarketplaceCurrencies.USD -> amountUSD
            amount.currency -> amount
            else -> exchangeRates.convert(date, this.amount)
        }
    }
}

data class DataTableColumn(
    val id: String,
    val title: String?,
    val cssClass: String? = null,
    val columnSpan: Int? = null,
    val cssStyle: String? = null,
    val tooltip: String? = null,
    val preSorted: AriaSortOrder? = null,
    /**
     * Title of the column in charts, if it differs from the [title], e.g. if the title is only a symbol.
     */
    val chartTitle: String? = title,
    /**
     * If `true`, the column contains the share of each row of a total, e.g. the percentage of sales,
     * which can be shown in a pie chart. The first column of each row must contain the name of the row.
     */
    val isShareOfTotal: Boolean = false,
    /**
     * ID of the values of this column for each day, which charts load from `/chart-series`,
     * see [dev.ja.marketplace.data.overview.OverviewDailyValues].
     */
    val dailyChartSeries: String? = null,
    /**
     * What the values of this column measure, e.g. sums of a period. Charts offer trends like moving averages only for
     * suitable columns, see [ChartMeasure].
     */
    val chartMeasure: ChartMeasure? = null,
    /**
     * The period of the values of the rows over time, see [DataTableRow.getChartHistory], e.g. the share of each customer
     * type per month. A line chart shows a series for each row. The first column of each row must contain its name.
     */
    val rowHistoryPeriod: ChartPeriod? = null,
)

data class SimpleTableSection(
    override val rows: List<DataTableRow>,
    override val title: String? = null,
    override val header: DataRowGroup? = null,
    override val footer: DataRowGroup? = null,
    override val cssClass: String? = null,
    override val columns: List<DataTableColumn>? = null,
) : DataTableSection {
    constructor(singleRow: DataTableRow) : this(listOf(singleRow))
}

data class SimpleRowGroup(
    override val rows: List<DataTableRow>,
    override val title: String? = null,
) : DataRowGroup {
    constructor(singleRow: DataTableRow) : this(listOf(singleRow))
}

data class SimpleDateTableRow(
    val values: Map<DataTableColumn, Any?>,
    val tooltips: Map<DataTableColumn, String?> = emptyMap(),
    override val cssClass: String? = null,
    val sortValues: Map<DataTableColumn, Long?> = emptyMap(),
    override val htmlId: String? = null,
    val chartHistories: Map<DataTableColumn, Map<YearMonthDay, Any?>> = emptyMap(),
) : DataTableRow {
    constructor(vararg values: Pair<DataTableColumn, Any?>) : this(values.toMap())

    override fun getValue(column: DataTableColumn): Any? {
        return values[column]
    }

    override fun getSortValue(column: DataTableColumn): Long? {
        return sortValues[column]
    }

    override fun getTooltip(column: DataTableColumn): String? {
        return tooltips[column]
    }

    override fun getChartHistory(column: DataTableColumn): Map<YearMonthDay, Any?>? {
        return chartHistories[column]
    }
}