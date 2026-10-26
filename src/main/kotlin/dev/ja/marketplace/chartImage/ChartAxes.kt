/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.chartImage

import dev.ja.marketplace.data.chart.ChartDetail
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToLong

/** The range and the step of the ticks of a y-axis. */
data class AxisRange(val min: Double, val max: Double, val step: Double) {
    /** The ticks without rounding errors of the floating point numbers. */
    val ticks: List<Double>
        get() {
            val count = ((max - min) / step).roundToLong().toInt()
            return List(count + 1) { BigDecimal(min + it * step).round(java.math.MathContext(12)).toDouble() }
        }
}

/** A tick of the date axis with its label. */
data class DateTick(val date: LocalDate, val label: String)

/**
 * The ticks and labels of the axes, ported from `model.js` and `format.js` of the web charts.
 */
object ChartAxes {
    private val factors = listOf(1.0, 2.0, 2.5, 5.0, 10.0)

    /** @return The smallest step of 1, 2, 2.5 or 5 times a power of ten, with at most [count] steps over the span. */
    fun niceStep(span: Double, count: Int): Double {
        val raw = span / max(1, count)
        if (!(raw > 0)) {
            return 1.0
        }
        return niceCeil(raw)
    }

    private fun niceCeil(value: Double): Double {
        val magnitude = 10.0.pow(floor(log10(value)))
        return factors.map { it * magnitude }.first { it >= value * (1 - 1e-9) }
    }

    /**
     * Ranges of y-axes, whose ticks are at the same heights, so that the grid lines of one axis are also at the values
     * of the other axis, like `alignedRanges` of `model.js`. The ranges include zero.
     *
     * @param extents The smallest and the largest value of each axis.
     * @param count The preferred number of steps.
     */
    fun alignedRanges(extents: List<Pair<Double, Double>>, count: Int): List<AxisRange> {
        val ranges = extents.map { (min, max) -> minOf(0.0, min) to maxOf(0.0, max) }
        var best: Triple<Double, Pair<Int, Int>, List<Double>>? = null
        for (total in max(2, count - 2)..count + 3) {
            for (below in 0 until total) {
                val above = total - below
                if (below == 0 && ranges.any { it.first < 0 }) {
                    continue
                }
                val steps = ranges.map { (min, max) ->
                    val needed = max(max / above, if (below > 0) -min / below else 0.0)
                    if (needed > 0) niceCeil(needed) else niceStep(1.0, total)
                }
                val unused = ranges.indices.sumOf { 1 - (ranges[it].second - ranges[it].first) / (total * steps[it]) }
                val score = unused + 0.05 * abs(total - count)
                if (best == null || score < best.first - 1e-9) {
                    best = Triple(score, below to above, steps)
                }
            }
        }
        val (below, above) = best!!.second
        return best.third.map { step -> AxisRange(if (below == 0) 0.0 else -below * step, above * step, step) }
    }

    /** @return The title of a y-axis of the unit, like `unitTitle` of `format.js`. */
    fun unitTitle(unit: String): String = when (unit) {
        "%" -> "Percentage (%)"
        "count" -> "Count"
        "ratio" -> "Ratio"
        else -> "Amount ($unit)"
    }

    /** @return A short number for the ticks of an axis, e.g. `1.2K`. */
    fun compact(value: Double): String {
        val magnitude = abs(value)
        val (scaled, suffix) = when {
            magnitude >= 1e9 -> value / 1e9 to "B"
            magnitude >= 1e6 -> value / 1e6 to "M"
            magnitude >= 1e3 -> value / 1e3 to "K"
            else -> return decimal(value, 2)
        }
        return decimal(scaled, 1) + suffix
    }

    /** @return The value with its unit, e.g. `12.5 %`, `1,234.56 EUR` or `17`. */
    fun value(value: Double, unit: String): String = when (unit) {
        "%" -> decimal(value, 2) + " %"
        "count", "ratio" -> decimal(value, 2)
        else -> decimal(value, 2) + " " + unit
    }

    private fun decimal(value: Double, maxFractionDigits: Int): String {
        val format = DecimalFormat("#,##0", DecimalFormatSymbols.getInstance(Locale.ENGLISH))
        format.maximumFractionDigits = maxFractionDigits
        format.roundingMode = RoundingMode.HALF_UP
        // -0 is shown as 0
        val text = format.format(value)
        return if (text == "-0") "0" else text
    }

    /**
     * The ticks of the date axis, at most about [maxTicks]:
     * - at the level Year the last day of each year and [lastValue] in the current year, like `yearEndTicks`, so that
     *   a tick shows the year of a value,
     * - at the level Month the last day of each month or of every 2, 3, 6 or 12 months,
     * - at the levels Week and Day the days, the Mondays or the first days of months.
     */
    fun dateTicks(start: LocalDate, end: LocalDate, detail: ChartDetail, lastValue: LocalDate?, maxTicks: Int): List<DateTick> {
        val limit = max(2, maxTicks)
        val days = start.until(end, java.time.temporal.ChronoUnit.DAYS) + 1
        val dayTicks = { if (days / 14 > limit) monthStartTicks(start, end, limit) else dayTicks(start, end, limit, detail) }
        val ticks = when (detail) {
            ChartDetail.Year -> yearTicks(start, end, lastValue, limit)
            ChartDetail.Month -> monthEndTicks(start, end, limit)
            ChartDetail.Week, ChartDetail.Day -> return dayTicks()
        }
        // a short range, e.g. this month at the level Month, has the ticks of its days
        return if (ticks.size < 2) dayTicks() else ticks
    }

    private fun yearTicks(start: LocalDate, end: LocalDate, lastValue: LocalDate?, limit: Int): List<DateTick> {
        val years = (start.year..end.year).toList()
        val step = listOf(1, 2, 5, 10, 20).firstOrNull { years.size / it <= limit } ?: 50
        return years.filter { it % step == 0 || step == 1 }.mapNotNull { year ->
            val yearEnd = LocalDate.of(year, 12, 31)
            val date = when {
                lastValue != null && lastValue.year == year && lastValue < yearEnd -> lastValue
                else -> yearEnd
            }
            date.takeIf { it in start..end }?.let { DateTick(it, year.toString()) }
        }
    }

    private fun monthEndTicks(start: LocalDate, end: LocalDate, limit: Int): List<DateTick> {
        val months = monthsBetween(start, end)
        val step = listOf(1, 2, 3, 6, 12, 24, 60).firstOrNull { months / it <= limit } ?: 120
        val ticks = mutableListOf<DateTick>()
        var month = start.withDayOfMonth(1)
        while (month <= end) {
            val monthEnd = month.with(TemporalAdjusters.lastDayOfMonth())
            if ((month.monthValue % step == 0 || step > 12 && month.monthValue == 12 && month.year % (step / 12) == 0) && monthEnd in start..end) {
                ticks += DateTick(monthEnd, if (step >= 12) month.year.toString() else monthLabel(month, ticks.isEmpty()))
            }
            month = month.plusMonths(1)
        }
        return ticks
    }

    private fun monthStartTicks(start: LocalDate, end: LocalDate, limit: Int): List<DateTick> {
        val months = monthsBetween(start, end)
        val step = listOf(1, 2, 3, 6, 12, 24, 60).firstOrNull { months / it <= limit } ?: 120
        val ticks = mutableListOf<DateTick>()
        var month = start.withDayOfMonth(1)
        while (month <= end) {
            val matches = if (step <= 12) (month.monthValue - 1) % step == 0 else month.monthValue == 1 && month.year % (step / 12) == 0
            if (matches && month >= start) {
                ticks += DateTick(month, if (step >= 12) month.year.toString() else monthLabel(month, ticks.isEmpty()))
            }
            month = month.plusMonths(1)
        }
        return ticks
    }

    private fun dayTicks(start: LocalDate, end: LocalDate, limit: Int, detail: ChartDetail): List<DateTick> {
        val days = start.until(end, java.time.temporal.ChronoUnit.DAYS) + 1
        val step = listOf(1L, 2L, 7L, 14L).firstOrNull { days / it <= limit } ?: 28L
        // weeks end on Sundays, the values of weeks are at their Sundays
        val first = when {
            detail == ChartDetail.Week || step >= 7 -> start.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
            else -> start
        }
        return generateSequence(first) { it.plusDays(step) }
            .takeWhile { it <= end }
            .map { DateTick(it, "${it.dayOfMonth} ${it.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)}") }
            .toList()
    }

    private fun monthLabel(month: LocalDate, isFirst: Boolean): String {
        val name = month.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
        return if (isFirst || month.monthValue == 1) "$name ${month.year}" else name
    }

    private fun monthsBetween(start: LocalDate, end: LocalDate): Int = (end.year - start.year) * 12 + end.monthValue - start.monthValue + 1
}
