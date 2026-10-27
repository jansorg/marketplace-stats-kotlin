/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace

import dev.ja.marketplace.data.DataTableColumn
import dev.ja.marketplace.data.RenderedDataTable

/**
 * The styles of the columns of a table as rules of the table instead of a class of each cell, e.g. of the 420,000 cells
 * of a list of licenses. HTML has no inheritance from `<col>` for the alignment, the rules select the cells by their
 * position (`dataTable.kte`). The declarations are the ones of the classes of the cells in `main.css`, the rules have
 * the specificity of a class of a cell (`:where`).
 */
object CellStyles {
    private val numbers = listOf("font-variant-numeric:tabular-nums lining-nums", "white-space:nowrap")
    private val right = listOf("text-align:right")

    /** The declarations of the classes of cells of `main.css`, an empty list for classes without rules of cells. */
    private val declarations = mapOf(
        "num" to numbers + right,
        "date" to numbers + right,
        "month" to numbers,
        "col-right" to right,
        "nowrap" to listOf("white-space:nowrap"),
        "highlighted" to listOf("background-color:var(--background-highlighted-color)"),
        "red" to listOf("color:var(--negative-color)"),
        "num-percentage" to emptyList(),
        "col-symbol" to emptyList(),
    )

    /**
     * @return The columns, whose classes are rules of the table, see [css]. A column with another class, e.g. `noprint`,
     *  keeps the class of its cells. Tables, whose sections have their own columns or whose columns span several cells,
     *  keep all classes, the position of a cell doesn't identify its column. Small tables keep their classes, the
     *  rules would be larger than the classes.
     */
    fun styledColumns(table: RenderedDataTable): Set<DataTableColumn> {
        if (table.sections.any { it.columns != null } || table.columns.any { (it.columnSpan ?: 1) > 1 }) {
            return emptySet()
        }
        if (table.sections.sumOf { it.rows.size } < MIN_ROWS) {
            return emptySet()
        }
        return table.columns.filterTo(HashSet()) { column ->
            val classes = classes(column)
            classes.isNotEmpty() && classes.all { it in declarations }
        }
    }

    /**
     * @param key The value of the attribute `data-cells` of the table.
     * @return The rules of the [styledColumns], `null` without such columns.
     */
    fun css(key: String, table: RenderedDataTable, styled: Set<DataTableColumn>): String? {
        val rules = table.columns.withIndex()
            .filter { (_, column) -> column in styled }
            .map { (index, column) -> index + 1 to classes(column).flatMap { declarations.getValue(it) }.distinct() }
            .filter { (_, declarations) -> declarations.isNotEmpty() }
            // columns with the same declarations share a rule
            .groupBy({ it.second }, { it.first })
        if (rules.isEmpty()) {
            return null
        }
        return rules.entries.joinToString("") { (declarations, positions) ->
            // cells, which span columns, aren't cells of the columns, e.g. the row "Show all" of collapsibleTables.js
            positions.joinToString(",") { ":where(table[data-cells=\"$key\"]>*>tr)>td:nth-child($it):where(:not([colspan]))" } + "{" + declarations.joinToString(";") + "}"
        }
    }

    /** The number of rows of a table with rules of its columns. */
    private const val MIN_ROWS = 50

    private fun classes(column: DataTableColumn): List<String> = column.cssClass?.split(' ')?.filter(String::isNotBlank) ?: emptyList()
}
