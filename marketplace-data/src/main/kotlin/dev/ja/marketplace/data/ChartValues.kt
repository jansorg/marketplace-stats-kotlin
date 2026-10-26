/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data

import dev.ja.marketplace.data.chart.ChartMetric
import dev.ja.marketplace.data.chart.ChartUnit
import dev.ja.marketplace.client.YearMonthDay
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import javax.money.MonetaryAmount

/**
 * A numeric value of a table cell, which can be shown in a chart.
 *
 * @param unit The currency code for amounts, `%` for percentages and `count` for numbers.
 * @param isIncomplete If the value may still change, see [IncompleteValue].
 */
data class ChartValue(val value: BigDecimal, val unit: String, val isIncomplete: Boolean = false)

/**
 * The period of the rows of a chartable table, see [DataTable.chartPeriod].
 */
enum class ChartPeriod {
    Day,
    Month,
    Year,
}

/**
 * What the values of a column of a chartable table measure. Charts offer trends only where they make sense,
 * e.g. a moving average of the sales of each month, but not of the MRR at the end of each month.
 */
enum class ChartMeasure {
    /** Sum of a period, e.g. the sales or the trials of a month. */
    Flow,

    /** Value at the end of a period, e.g. the MRR or the number of licenses. */
    Stock,

    /** Percentage or ratio, e.g. a churn rate. */
    Ratio,
}

/**
 * Extracts the data of chartable tables, see [DataTable.isChartable].
 */
object ChartValues {
    private val yearMonthPattern = Regex("""\d{4}-\d{2}""")

    /**
     * The values of months and years are located at the end of their period, e.g. the number of licenses at the end of a
     * month, the value of the current month or year at the given date, e.g. today. The ticks of the years are at the end
     * of the years, and the values per week or per day of yearly tables are the sums of the year before each day.
     *
     * @return The date of a row as ISO date, based on the value of the first column of the row.
     */
    fun xValue(period: ChartPeriod, firstColumnValue: Any?, today: YearMonthDay = YearMonthDay.now()): String? {
        val date = when (firstColumnValue) {
            is YearMonthDay -> firstColumnValue
            is String -> when {
                yearMonthPattern.matches(firstColumnValue) -> YearMonthDay.parse("$firstColumnValue-01")
                else -> return null
            }

            is Int -> when (firstColumnValue) {
                in 1970..9999 -> YearMonthDay(firstColumnValue, 1, 1)
                else -> return null
            }

            else -> return null
        }

        return when (period) {
            ChartPeriod.Day -> date
            ChartPeriod.Month -> minOf(YearMonthDay.lastOfMonth(date.year, date.month), maxOf(today, date))
            ChartPeriod.Year -> minOf(YearMonthDay(date.year, 12, 31), maxOf(today, YearMonthDay(date.year, 1, 1)))
        }.asIsoString
    }

    /**
     * The values of a row over time, see [DataTableRow.getChartHistory], e.g.
     * `{"unit":"%","points":[["2026-09-30",45.20,false],["2026-10-23",50.00,true]]}`. The dates are located like the
     * rows of chartable tables, see [xValue], the last value of a point is `true` for values, which may still change.
     *
     * @return The JSON of the values, or `null` if there are no values, which can be shown.
     */
    fun history(period: ChartPeriod, values: Map<YearMonthDay, Any?>, today: YearMonthDay = YearMonthDay.now()): String? {
        val points = values.entries
            .sortedBy { it.key }
            .mapNotNull { (date, cellValue) -> value(cellValue)?.let { xValue(period, date, today)!! to it } }
        val unit = points.firstOrNull()?.second?.unit ?: return null
        return points.joinToString(",", "{\"unit\":\"$unit\",\"points\":[", "]}") { (date, value) ->
            "[\"$date\",${value.value.toPlainString()},${value.isIncomplete}]"
        }
    }

    /**
     * @return The name of a row of a table with share columns, based on the value of the first column of the row.
     */
    fun category(firstColumnValue: Any?): String? {
        return when (firstColumnValue) {
            null, NoValue -> null
            is IncompleteValue -> category(firstColumnValue.value)
            else -> firstColumnValue.toString().takeIf(String::isNotBlank)
        }
    }

    /**
     * @return The unit of the values of a column with a [DataTableColumn.chartMetric], e.g. the currency code of
     *  amounts, or `null` if the table has no amounts in the column.
     */
    fun metricUnit(table: RenderedDataTable, column: DataTableColumn): String? {
        val metricId = column.chartMetric ?: return null
        val unit = ChartMetric.byId(metricId)?.unit
        // the shares of the rows, e.g. of the customer types, are percentages
            ?: if (metricId == ChartMetric.CUSTOMER_TYPE_SHARE) ChartUnit.Percentage else null
        return when (unit) {
            ChartUnit.Count, ChartUnit.Percentage, ChartUnit.Ratio -> unit.id("")
            // the displayed currency of the amounts
            else -> table.sections.asSequence().flatMap { it.rows }.firstNotNullOfOrNull { value(it.getValue(column)) }?.unit
        }
    }

    fun value(cellValue: Any?): ChartValue? {
        return when (cellValue) {
            is IncompleteValue -> value(cellValue.value)?.copy(isIncomplete = true)
            is LinkedChurnRate -> ChartValue(cellValue.rate.value.setScale(2, RoundingMode.HALF_UP), "%")
            is MonetaryAmount -> ChartValue(
                cellValue.number.numberValue(BigDecimal::class.java).setScale(2, RoundingMode.HALF_UP),
                cellValue.currency.currencyCode
            )
            // PercentageValue.ZERO is rendered as "—" in tables, but it's 0% in the chartable tables, e.g. no converted trials
            is PercentageValue -> ChartValue(cellValue.value.setScale(2, RoundingMode.HALF_UP), "%")
            is BigInteger -> ChartValue(cellValue.toBigDecimal(), "count")
            is Int -> ChartValue(cellValue.toBigDecimal(), "count")
            is Long -> ChartValue(cellValue.toBigDecimal(), "count")
            else -> null
        }
    }
}
