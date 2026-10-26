/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.chart

import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.data.ChartPeriod
import java.time.DayOfWeek

/**
 * The window of the values of a chart at each day: sums like the sales and rates like the churn are calculated for
 * the days of the window, which ends at the day, e.g. the sales of the 12 months before each day.
 *
 * The rolling windows of months, quarters and years end with the calendar period at its last day, e.g. the window of
 * a month at September 30 is September. The window of a week at a Sunday is the calendar week from Monday.
 * The calendar windows start at the first day of the calendar period of each day, e.g. the sales of the month until the
 * day. At the last day of a period and at today, their values are the values of the tables of the periods.
 *
 * Values at a day, like the MRR, don't depend on the window, see [ChartMetric.usesWindow].
 * New windows only need [start] and [currentPeriodStart].
 */
enum class ChartWindow(val id: String) {
    Day("day"),
    Week("week"),
    Month("month"),
    Quarter("quarter"),
    Year("year"),
    CalendarMonth("calendar-month"),
    CalendarQuarter("calendar-quarter"),
    CalendarYear("calendar-year");

    /** @return The first day of the window, which ends at [day]. */
    fun start(day: YearMonthDay): YearMonthDay {
        return when (this) {
            Day -> day
            Week -> day.add(0, 0, -6)
            Month -> monthsBefore(day, 1)
            Quarter -> monthsBefore(day, 3)
            Year -> monthsBefore(day, 12)
            CalendarMonth -> YearMonthDay.firstOfMonth(day.year, day.month)
            CalendarQuarter -> YearMonthDay.firstOfMonth(day.year, (day.month - 1) / 3 * 3 + 1)
            CalendarYear -> YearMonthDay(day.year, 1, 1)
        }
    }

    /**
     * @return The first day of the calendar period, which contains [today], e.g. the first day of the current month.
     *  Rates of the days from it may still change, e.g. the churn of the current month like in the tables.
     */
    fun currentPeriodStart(today: YearMonthDay): YearMonthDay {
        return when (this) {
            Day -> today
            Week -> today.add(0, 0, -(today.toLocalDate().dayOfWeek.value - 1))
            Month -> CalendarMonth.start(today)
            Quarter -> CalendarQuarter.start(today)
            Year -> CalendarYear.start(today)
            CalendarMonth, CalendarQuarter, CalendarYear -> start(today)
        }
    }

    /** The rolling window of a calendar window, e.g. of a month, the moving averages use it. */
    val rolling: ChartWindow
        get() = when (this) {
            CalendarMonth -> Month
            CalendarQuarter -> Quarter
            CalendarYear -> Year
            else -> this
        }

    /** The approximate number of days of the window, e.g. to compare it with the period of a moving average. */
    val approximateDays: Int
        get() = when (rolling) {
            Day -> 1
            Week -> 7
            Month -> 28
            Quarter -> 90
            else -> 365
        }

    companion object {
        fun byId(id: String): ChartWindow? = entries.firstOrNull { it.id == id }

        /**
         * @return The first day of the [months] before [day]: the calendar months at the end of a month, otherwise
         *  from the day after the same day of the first month.
         */
        fun monthsBefore(day: YearMonthDay, months: Int): YearMonthDay {
            return when (day) {
                YearMonthDay.lastOfMonth(day.year, day.month) -> YearMonthDay.firstOfMonth(day.year, day.month).add(0, -(months - 1), 0)
                else -> day.add(0, -months, 1)
            }
        }
    }
}

/**
 * The dates of the values of a chart: each day, the last day of each week, month or year. Today is always included,
 * e.g. the value of the current month.
 */
enum class ChartDetail(val id: String) {
    Day("day"),
    Week("week"),
    Month("month"),
    Year("year");

    /** @return The indices of the [days] of the values. */
    fun indices(days: List<YearMonthDay>): List<Int> {
        return days.indices.filter { index ->
            val day = days[index]
            index == days.lastIndex || when (this) {
                Day -> true
                // the last day of each week, i.e. each Sunday
                Week -> day.toLocalDate().dayOfWeek == DayOfWeek.SUNDAY
                Month -> day == YearMonthDay.lastOfMonth(day.year, day.month)
                Year -> day.month == 12 && day.day == 31
            }
        }
    }

    companion object {
        fun byId(id: String): ChartDetail? = entries.firstOrNull { it.id == id }
    }
}

/**
 * The window and the detail level of a chart.
 */
data class ChartLevel(val window: ChartWindow, val detail: ChartDetail) {
    companion object {
        /**
         * @return The level of the values of a table of the [period], e.g. the sums of each calendar month at its end
         *  and of the current month until today.
         */
        fun of(period: ChartPeriod): ChartLevel = when (period) {
            ChartPeriod.Day -> ChartLevel(ChartWindow.Day, ChartDetail.Day)
            ChartPeriod.Month -> ChartLevel(ChartWindow.CalendarMonth, ChartDetail.Month)
            ChartPeriod.Year -> ChartLevel(ChartWindow.CalendarYear, ChartDetail.Year)
        }
    }
}
