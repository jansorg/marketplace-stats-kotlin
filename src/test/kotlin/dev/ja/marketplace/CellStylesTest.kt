/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace

import dev.ja.marketplace.client.LicenseInfo
import dev.ja.marketplace.data.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CellStylesTest {
    private val name = DataTableColumn("name", "Name")
    private val amount = DataTableColumn("amount", "Amount", "num")
    private val date = DataTableColumn("date", "Date", "date")
    private val hidden = DataTableColumn("hidden", "Hidden", "noprint")
    private val rate = DataTableColumn("rate", "Rate", "num num-percentage")

    private class TestTable(override val columns: List<DataTableColumn>) : SimpleDataTable("Test") {
        override suspend fun createSections(): List<DataTableSection> = emptyList()
        override suspend fun process(licenseInfo: LicenseInfo) = Unit
    }

    private fun table(rows: Int, columns: List<DataTableColumn> = listOf(name, amount, date, hidden, rate), sectionColumns: List<DataTableColumn>? = null): RenderedDataTable {
        val section = SimpleTableSection(List(rows) { SimpleDateTableRow(name to "row $it") }, columns = sectionColumns)
        return RenderedDataTable(TestTable(columns), listOf(section))
    }

    @Test
    fun rulesOfTheColumnsOfLargeTables() {
        val table = table(rows = 60)
        val styled = CellStyles.styledColumns(table)
        // columns without a class and with an unknown class, e.g. noprint of print.css, keep the classes of the cells
        assertEquals(setOf(amount, date, rate), styled)
        val css = CellStyles.css("t1", table, styled)!!
        // the amounts and the dates share a rule, cells spanning columns aren't selected
        assertTrue(":where(table[data-cells=\"t1\"]>*>tr)>td:nth-child(2):where(:not([colspan]))," in css, css)
        assertTrue("td:nth-child(3)" in css && "td:nth-child(5)" in css)
        assertTrue("text-align:right" in css && "white-space:nowrap" in css)
        assertFalse("td:nth-child(1)" in css || "td:nth-child(4)" in css)
    }

    @Test
    fun smallTablesAndTablesWithOwnColumnsKeepTheirClasses() {
        assertEquals(emptySet<DataTableColumn>(), CellStyles.styledColumns(table(rows = 10)))
        // the sections have their own columns, the position of a cell doesn't identify a column of the table
        assertEquals(emptySet<DataTableColumn>(), CellStyles.styledColumns(table(rows = 60, sectionColumns = listOf(amount))))
        assertEquals(emptySet<DataTableColumn>(), CellStyles.styledColumns(table(rows = 60, columns = listOf(name, amount.copy(columnSpan = 2)))))
        assertNull(CellStyles.css("t1", table(rows = 10), emptySet()))
    }
}
