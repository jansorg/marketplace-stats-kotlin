/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace

import dev.ja.marketplace.client.LicenseInfo
import dev.ja.marketplace.data.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class TableChunksTest {
    private val name = DataTableColumn("name", "Name")
    private val share = DataTableColumn("share", "Share", "num", isShareOfTotal = true)

    private class TestTable(
        override val columns: List<DataTableColumn>,
        override val isChartable: Boolean = false,
        override val collapsedRowCount: Int? = null,
    ) : SimpleDataTable("Test") {
        override suspend fun createSections(): List<DataTableSection> = emptyList()
        override suspend fun process(licenseInfo: LicenseInfo) = Unit
    }

    private fun section(rows: Int) = SimpleTableSection(List(rows) { SimpleDateTableRow(name to "row $it") })

    private fun table(rows: Int, table: TestTable = TestTable(listOf(name)), sections: Int = 1): RenderedDataTable {
        return RenderedDataTable(table, List(sections) { section(rows) })
    }

    @Test
    fun rowsOfLargeTablesAreSplit() {
        assertEquals(TableChunks.ROWS, TableChunks.rowsPerChunk(table(rows = 10_000), hasCellStyles = true))
        // a table of two chunks or less isn't split
        assertNull(TableChunks.rowsPerChunk(table(rows = 2 * TableChunks.ROWS), hasCellStyles = true))
        assertEquals(TableChunks.ROWS, TableChunks.rowsPerChunk(table(rows = 2 * TableChunks.ROWS + 1), hasCellStyles = true))
    }

    @Test
    fun tablesWhichNeedASingleTableAreNotSplit() {
        // the tables of the rows share the rules of the cells
        assertNull(TableChunks.rowsPerChunk(table(rows = 5_000), hasCellStyles = false))
        assertNull(TableChunks.rowsPerChunk(table(rows = 5_000, sections = 2), hasCellStyles = true))
        // the charts and the collapsed rows use the rows of the first table
        assertNull(TableChunks.rowsPerChunk(table(rows = 5_000, TestTable(listOf(name), isChartable = true)), hasCellStyles = true))
        assertNull(TableChunks.rowsPerChunk(table(rows = 5_000, TestTable(listOf(name, share))), hasCellStyles = true))
        assertNull(TableChunks.rowsPerChunk(table(rows = 5_000, TestTable(listOf(name), collapsedRowCount = 10)), hasCellStyles = true))
    }

    @Test
    fun tablesOfTheRowsAreNotSortable() {
        assertEquals("small table-striped table-column-wide", TableChunks.chunkTableClasses("small table-striped table-column-wide sortable"))
        assertEquals("small table-striped", TableChunks.chunkTableClasses("small table-striped "))
    }
}
