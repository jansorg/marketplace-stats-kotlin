/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.mcp

import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.client.YearMonthDayRange
import dev.ja.marketplace.data.*
import kotlinx.serialization.json.*
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import javax.money.MonetaryAmount

/**
 * Converts the rendered data tables into JSON for the MCP tools.
 *
 * Rows are written as arrays of values in the order of the columns. Each column has a unique key and a unit,
 * e.g. the currency of monetary amounts or `%` for percentages, the values are plain numbers.
 * Churn rates are written as `{"percent": 5.0, "churned": 1, "previousActive": 20}`.
 * Values, which may still change, e.g. the recurring revenue of the current month,
 * are wrapped as `{"value": ..., "incomplete": true}`, rows of the current period are listed in `incompleteRows`.
 * Tooltips of cells with counts, e.g. the number of converted trials, are returned as `notes`.
 */
object DataTableJson {
    private val yearMonthPattern = Regex("""(\d{4})-(\d{2})""")

    /**
     * @param rowFilter If not `null`, only rows whose date (the value of the first column) intersects
     *  this range are kept. Footers of sections with removed rows are dropped, because they summarize all rows.
     * @return `columns` (if all sections have the same columns, otherwise each section has its columns) and `sections`.
     */
    fun toJson(table: RenderedDataTable, rowFilter: YearMonthDayRange? = null, today: YearMonthDay = YearMonthDay.now()): JsonObject {
        class Section(val source: DataTableSection, val columns: List<DataTableColumn>, val rows: List<DataTableRow>, val footer: List<DataTableRow>)

        val sections = table.sections.mapNotNull { section ->
            val columns = section.columns ?: table.columns
            val rows = section.rows.filter { row ->
                rowFilter == null || rowPeriod(row.getValue(columns.first()))?.isIntersecting(rowFilter) != false
            }
            when {
                rows.isEmpty() && section.rows.isNotEmpty() -> null
                else -> Section(section, columns, rows, section.footer?.rows?.takeIf { rows.size == section.rows.size } ?: emptyList())
            }
        }

        val sharedColumns = sections.map { it.columns }.distinct().singleOrNull()
        fun columnsJson(columns: List<DataTableColumn>): JsonArray {
            val keys = columnKeys(columns)
            val values = sections.filter { it.columns == columns }.flatMap { it.rows + it.footer }
            return buildJsonArray {
                for ((column, key) in columns.zip(keys)) {
                    add(buildJsonObject {
                        put("key", key)
                        put("title", column.chartTitle ?: column.title ?: column.id)
                        values.firstNotNullOfOrNull { unit(it.getValue(column)) }?.let { put("unit", it) }
                        column.tooltip?.let { put("description", it) }
                    })
                }
            }
        }

        return buildJsonObject {
            sharedColumns?.let { put("columns", columnsJson(it)) }
            put("sections", buildJsonArray {
                for (section in sections) {
                    val keys = columnKeys(section.columns)
                    add(buildJsonObject {
                        section.source.title?.takeIf(String::isNotBlank)?.let { put("title", it) }
                        if (sharedColumns == null) {
                            put("columns", columnsJson(section.columns))
                        }
                        put("rows", rowsToJson(section.rows, section.columns))
                        if (section.footer.isNotEmpty()) {
                            put("footer", rowsToJson(section.footer, section.columns))
                        }

                        val incompleteRows = section.rows.indices.filter { index ->
                            rowPeriod(section.rows[index].getValue(section.columns.first()))?.let { today in it } == true
                        }
                        if (incompleteRows.isNotEmpty()) {
                            put("incompleteRows", buildJsonArray { incompleteRows.forEach { add(it) } })
                        }

                        val notes = buildJsonArray {
                            for ((isFooter, rows) in listOf(false to section.rows, true to section.footer)) {
                                for ((index, row) in rows.withIndex()) {
                                    for ((column, key) in section.columns.zip(keys)) {
                                        val tooltip = row.getTooltip(column)?.trim()
                                        // only tooltips with counts, other tooltips explain the column or the incomplete value,
                                        // the counts of churn rates are part of their values
                                        if (tooltip.isNullOrEmpty() || tooltip.none(Char::isDigit) || tooltip == column.tooltip
                                            || unwrap(row.getValue(column)) is LinkedChurnRate
                                        ) {
                                            continue
                                        }
                                        add(buildJsonObject {
                                            put(if (isFooter) "footerRow" else "row", index)
                                            put("column", key)
                                            put("text", tooltip)
                                        })
                                    }
                                }
                            }
                        }
                        if (notes.isNotEmpty()) {
                            put("notes", notes)
                        }
                    })
                }
            })
        }
    }

    /**
     * @return Unique keys of the columns in camel case, derived from the titles, e.g. `totalSales`.
     */
    fun columnKeys(columns: List<DataTableColumn>): List<String> {
        val used = HashMap<String, Int>()
        return columns.map { column ->
            val words = (column.chartTitle ?: column.title ?: column.id)
                .split(Regex("[^A-Za-z0-9]+"))
                .filter(String::isNotEmpty)
            val base = words.mapIndexed { index, word ->
                when {
                    index == 0 && word.length > 1 && word.all(Char::isUpperCase) -> word.lowercase()
                    index == 0 -> word.replaceFirstChar(Char::lowercaseChar)
                    else -> word.replaceFirstChar(Char::uppercaseChar)
                }
            }.joinToString("").ifEmpty { column.id }
            val count = used.merge(base, 1, Int::plus)!!
            if (count == 1) base else "$base$count"
        }
    }

    private fun rowsToJson(rows: List<DataTableRow>, columns: List<DataTableColumn>): JsonArray {
        return buildJsonArray {
            for (row in rows) {
                add(buildJsonArray {
                    for (column in columns) {
                        add(cellValue(row.getValue(column)))
                    }
                })
            }
        }
    }

    private fun unwrap(value: Any?): Any? = (value as? IncompleteValue)?.value ?: value

    private fun unit(value: Any?): String? {
        return when (value) {
            is IncompleteValue -> unit(value.value)
            is MonetaryAmount -> value.currency.currencyCode
            is PercentageValue, is LinkedChurnRate -> "%"
            is RatingValue -> "rating"
            is Iterable<*> -> value.firstNotNullOfOrNull(::unit)
            else -> null
        }
    }

    /**
     * @return The period of a row, based on the value of its first column, e.g. a day, a month `2024-05` or a year.
     */
    fun rowPeriod(firstColumnValue: Any?): YearMonthDayRange? {
        return when (firstColumnValue) {
            is YearMonthDay -> YearMonthDayRange.of(firstColumnValue)
            is Int -> when (firstColumnValue) {
                in 1970..9999 -> YearMonthDayRange.ofYear(firstColumnValue)
                else -> null
            }

            is String -> yearMonthPattern.matchEntire(firstColumnValue)?.let {
                YearMonthDayRange.ofMonth(it.groupValues[1].toInt(), it.groupValues[2].toInt())
            }

            else -> null
        }
    }

    fun cellValue(value: Any?): JsonElement {
        return when (value) {
            null, NoValue -> JsonNull
            is IncompleteValue -> buildJsonObject {
                put("value", cellValue(value.value))
                put("incomplete", true)
            }

            is LinkedChurnRate -> when {
                value.previousActiveCount > 0 -> buildJsonObject {
                    put("percent", percent(value.rate))
                    put("churned", value.churnedCount)
                    put("previousActive", value.previousActiveCount)
                }

                else -> JsonPrimitive(percent(value.rate))
            }

            is MonetaryAmount -> JsonPrimitive(amount(value))
            is PercentageValue -> JsonPrimitive(percent(value))
            is RatingValue -> JsonPrimitive(value.value)
            is BigInteger -> JsonPrimitive(value)
            is BigDecimal -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            is YearMonthDay -> JsonPrimitive(value.asIsoString)
            is LinkedCustomer -> JsonPrimitive(value.id)
            is LinkedLicense -> JsonPrimitive(value.id)
            is LinkedRefNum -> JsonPrimitive(value.refNum)
            is Iterable<*> -> JsonArray(value.map(::cellValue))
            is Enum<*> -> JsonPrimitive(value.toolName)
            else -> JsonPrimitive(value.toString())
        }
    }

    fun amount(value: MonetaryAmount): BigDecimal {
        return value.number.numberValue(BigDecimal::class.java).setScale(2, RoundingMode.HALF_UP)
    }

    private fun percent(value: PercentageValue): BigDecimal {
        return value.value.setScale(2, RoundingMode.HALF_UP)
    }
}
