/*
 * Copyright (c) 2023-2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data

import dev.ja.marketplace.client.MarketplaceCurrencies
import dev.ja.marketplace.client.WithAmounts
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

    suspend fun renderTable(): RenderedDataTable
}

interface DataTableRow {
    fun getValue(column: DataTableColumn): Any?
    fun getSortValue(column: DataTableColumn): Long? = null
    fun getTooltip(column: DataTableColumn): String? = null
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
}