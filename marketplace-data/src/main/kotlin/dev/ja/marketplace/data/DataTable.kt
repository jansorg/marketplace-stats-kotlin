/*
 * Copyright (c) 2023 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data

/**
 * Tabular data calculated based on the Marketplace data.
 */
interface DataTable {
    val id: String?
    val title: String?
    val columns: List<DataTableColumn>
    val sections: List<DataTableSection>

    val cssClass: String?
    val header: DataRowGroup?

    val isLimitedRendering: Boolean
        get() {
            return false
        }

    val isEmpty: Boolean
        get() {
            return sections.all { it.rows.isEmpty() }
        }
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
    final override val sections: List<DataTableSection> by lazy { createSections() }

    protected abstract fun createSections(): List<DataTableSection>
}

data class DataTableColumn(
    val id: String,
    val title: String?,
    val cssClass: String? = null,
    val columnSpan: Int? = null,
    val cssStyle: String? = null,
    val tooltip: String? = null,
)

data class SimpleTableSection(
    override val rows: List<DataTableRow>,
    override val title: String? = null,
    override val header: DataRowGroup? = null,
    override val footer: DataRowGroup? = null,
    override val cssClass: String? = null,
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