/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace

import dev.ja.marketplace.data.RenderedDataTable

/**
 * The rows of large tables, e.g. of a list of all licenses, are split into tables of [ROWS] rows, which the browser
 * skips while they're off-screen (`content-visibility: auto` of `.table-chunk`, `dataTable.kte`). A table with tens of
 * thousands of rows is laid out again as a whole after each part of the page, which the browser receives, and each
 * cell needs its own layout and paint data, which the browser keeps in memory.
 *
 * The first table contains the header and the first rows, the other tables only rows and the last also the footer.
 * `chunkedTables.js` gives the other tables the widths of the columns of the first table and sorts the rows of all
 * tables, when the first table is sorted.
 */
object TableChunks {
    /** The number of rows of each table. It's even, so that the stripes of the rows continue in the next table. */
    const val ROWS = 500

    /**
     * @param hasCellStyles If the columns of the table have the rules of [CellStyles], the cells of the tables of the
     *  rows are styled by the attribute `data-cells`, which all the tables share.
     * @return The number of rows of each table, `null` if the rows of the table aren't split. Only large tables with
     *  a single section and the cells of their columns are split. The charts read the rows of a single table and the
     *  collapsed rows are rows of the first table, such tables aren't split.
     */
    fun rowsPerChunk(table: RenderedDataTable, hasCellStyles: Boolean): Int? {
        if (!hasCellStyles || table.sections.size != 1 || table.collapsedRowCount != null) {
            return null
        }
        if (table.isChartable || table.columns.any { it.isShareOfTotal || it.rowHistoryPeriod != null }) {
            return null
        }
        return if (table.sections[0].rows.size > 2 * ROWS) ROWS else null
    }

    /**
     * @return The classes of the tables of the rows: the ones of the first table without `sortable`, they have no
     *  header, which could be sorted.
     */
    fun chunkTableClasses(tableClasses: String): String {
        return tableClasses.split(' ').filter { it.isNotBlank() && it != "sortable" }.joinToString(" ")
    }
}
