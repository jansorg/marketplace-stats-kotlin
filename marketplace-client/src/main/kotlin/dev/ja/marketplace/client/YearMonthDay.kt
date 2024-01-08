/*
 * Copyright (c) 2023-2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client

import kotlinx.datetime.*
import kotlinx.datetime.TimeZone
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.*
import java.util.concurrent.ConcurrentHashMap

@Serializable(with = YearMonthDateSerializer::class)
data class YearMonthDay(
    val year: Int,
    val month: Int,
    val day: Int,
    val timezone: TimeZone = MarketplaceTimeZone
) : Comparable<YearMonthDay> {
    private val instant = LocalDate(year, month, day).atStartOfDayIn(timezone)

    val asIsoString: String by lazy {
        String.format("%04d-%02d-%02d", year, month, day)
    }

    fun rangeTo(end: YearMonthDay): YearMonthDayRange {
        return YearMonthDayRange(this, end)
    }

    fun rangeTo(end: Instant): YearMonthDayRange {
        return YearMonthDayRange(this, of(end, timezone))
    }

    override fun toString(): String {
        return asIsoString
    }

    val sortValue: Long
        get() {
            return instant.epochSeconds
        }

    fun add(years: Int, months: Int, days: Int): YearMonthDay {
        if (years == 0 && months == 0 && days == 0) {
            return this
        }

        return of(
            instant.plus(years, DateTimeUnit.YEAR, timezone)
                .plus(months, DateTimeUnit.MONTH, timezone)
                .plus(days, DateTimeUnit.DAY, timezone)
        )
    }

    override operator fun compareTo(other: YearMonthDay): Int {
        return instant.compareTo(other.instant)
    }

    fun daysUntil(date: YearMonthDay): Int {
        return instant.daysUntil(date.instant, timezone)
    }

    companion object {
        fun parse(date: String): YearMonthDay {
            val (y, m, d) = date.split('-')
            return YearMonthDay(y.toInt(), m.toInt(), d.toInt())
        }

        fun now(): YearMonthDay {
            return of(Clock.System.now())
        }

        fun of(date: java.time.LocalDate, timezone: TimeZone = MarketplaceTimeZone): YearMonthDay {
            val timezoneDate = date.atStartOfDay(timezone.toJavaZoneId())
            return YearMonthDay(timezoneDate.year, timezoneDate.monthValue, timezoneDate.dayOfMonth)
        }

        fun of(date: Instant, timezone: TimeZone = MarketplaceTimeZone): YearMonthDay {
            return instantCache.computeIfAbsent(date) {
                val timezoneDate = date.toLocalDateTime(timezone)
                YearMonthDay(timezoneDate.year, timezoneDate.monthNumber, timezoneDate.dayOfMonth)
            }
        }

        private val instantCache = ConcurrentHashMap<Instant, YearMonthDay>()
    }
}

/**
 * Range of year-month-day, as used by the Marketplace.
 */
@Serializable
data class YearMonthDayRange(
    @SerialName("start")
    val start: YearMonthDay,
    @SerialName("end")
    val end: YearMonthDay,
) : Comparable<YearMonthDayRange> {
    operator fun contains(item: YearMonthDay): Boolean {
        return item in start..end
    }

    fun isBefore(other: YearMonthDayRange): Boolean {
        return end < other.end
    }

    fun isIntersecting(other: YearMonthDayRange): Boolean {
        return this == other
                || start in other || end in other
                || other.start in this || other.end in this
    }

    override fun compareTo(other: YearMonthDayRange): Int {
        return when {
            start != other.start -> start.compareTo(other.start)
            else -> end.compareTo(other.end)
        }
    }

    override fun toString(): String {
        return asIsoStringRange()
    }

    fun days(): Sequence<YearMonthDay> {
        return generateSequence(start) {
            when {
                it < end -> it.add(0, 0, 1)
                else -> null
            }
        }
    }

    fun stepSequence(years: Int, months: Int = 0, days: Int = 0): Sequence<YearMonthDayRange> {
        val items = mutableListOf<YearMonthDayRange>()
        var rangeStart = start
        while (rangeStart < end) {
            val rangeEnd = rangeStart.add(years, months, days)
            items += when {
                rangeEnd > end -> rangeStart.rangeTo(end)
                else -> rangeStart.rangeTo(rangeEnd)
            }
            rangeStart = rangeEnd.add(0, 0, 1)
        }
        return items.asSequence()
    }

    fun shift(years: Int, months: Int, days: Int): YearMonthDayRange {
        return YearMonthDayRange(start.add(years, months, days), end.add(years, months, days))
    }

    fun expandEnd(years: Int, months: Int, days: Int): YearMonthDayRange {
        if (years == 0 && months == 0 && days == 0) {
            return this
        }
        return YearMonthDayRange(start, end.add(years, months, days))
    }

    private fun asIsoStringRange(): String {
        return "${start.asIsoString} - ${end.asIsoString}"
    }

    companion object {
        fun currentWeek(timezone: TimeZone = MarketplaceTimeZone): YearMonthDayRange {
            val firstDayOfWeek = WeekFields.of(Locale.getDefault()).firstDayOfWeek
            val firstOfWeek = java.time.LocalDate.now(timezone.toJavaZoneId()).with(TemporalAdjusters.previousOrSame(firstDayOfWeek))
            val lastOfWeek = firstOfWeek.plusDays(6)
            return YearMonthDayRange(YearMonthDay.of(firstOfWeek), YearMonthDay.of(lastOfWeek))
        }

        fun ofYear(year: Int): YearMonthDayRange {
            return YearMonthDayRange(YearMonthDay(year, 1, 1), YearMonthDay(year, 12, 31))
        }

        fun ofMonth(year: Int, month: Int): YearMonthDayRange {
            val first = YearMonthDay(year, month, 1)
            val last = YearMonth.of(year, month).atEndOfMonth()
            return YearMonthDayRange(first, YearMonthDay(year, month, last.dayOfMonth))
        }
    }
}