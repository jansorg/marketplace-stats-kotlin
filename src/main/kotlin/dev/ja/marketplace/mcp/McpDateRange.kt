/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.mcp

import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.client.YearMonthDayRange
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * Optional date range of a tool call, both dates are inclusive.
 * A missing [start] or [end] means that the range is open on that side.
 */
data class McpDateRange(val start: YearMonthDay?, val end: YearMonthDay?) {
    val isUnbounded: Boolean
        get() = start == null && end == null

    fun toRange(): YearMonthDayRange {
        return YearMonthDayRange(start ?: YearMonthDay.MIN, end ?: YearMonthDay.MAX)
    }

    companion object {
        val All = McpDateRange(null, null)

        /**
         * @throws IllegalArgumentException If a date isn't a valid ISO date or if the start is after the end.
         */
        fun parse(startDate: String?, endDate: String?): McpDateRange {
            val start = parseDate("startDate", startDate)
            val end = parseDate("endDate", endDate)
            require(start == null || end == null || start <= end) {
                "startDate ${start!!.asIsoString} must not be after endDate ${end!!.asIsoString}"
            }
            return McpDateRange(start, end)
        }

        internal fun parseDate(name: String, value: String?): YearMonthDay? {
            if (value.isNullOrBlank()) {
                return null
            }

            return try {
                YearMonthDay.of(LocalDate.parse(value.trim()))
            } catch (_: DateTimeParseException) {
                throw IllegalArgumentException("$name must be an ISO date in the format YYYY-MM-DD, but was '$value'")
            }
        }
    }
}
