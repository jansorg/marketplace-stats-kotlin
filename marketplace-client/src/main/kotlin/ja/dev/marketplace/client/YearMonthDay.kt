package ja.dev.marketplace.client

import kotlinx.datetime.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.*

@Serializable(with = YearMonthDateSerializer::class)
data class YearMonthDay(val year: Int, val month: Int, val day: Int) : Comparable<YearMonthDay> {
    private val instant = LocalDate(year, month, day).atStartOfDayIn(Marketplace.ServerTimeZone)

    fun rangeTo(end: YearMonthDay): YearMonthDayRange {
        return YearMonthDayRange(this, end)
    }

    fun rangeTo(end: Instant): YearMonthDayRange {
        return YearMonthDayRange(this, of(end))
    }

    fun asIsoString(): String {
        return String.format("%04d-%02d-%02d", year, month, day)
    }

    override fun toString(): String {
        return asIsoString()
    }

    fun add(years: Int, months: Int, days: Int): YearMonthDay {
        return of(
            instant.plus(years, DateTimeUnit.YEAR, Marketplace.ServerTimeZone)
                .plus(months, DateTimeUnit.MONTH, Marketplace.ServerTimeZone)
                .plus(days, DateTimeUnit.DAY, Marketplace.ServerTimeZone)
        )
    }

    override operator fun compareTo(other: YearMonthDay): Int {
        return instant.compareTo(other.instant)
    }

    companion object {
        fun now(): YearMonthDay {
            return of(Clock.System.now())
        }

        fun of(date: LocalDateTime): YearMonthDay {
            return YearMonthDay(date.year, date.monthNumber, date.dayOfMonth)
        }

        fun of(date: java.time.LocalDateTime): YearMonthDay {
            return YearMonthDay(date.year, date.monthValue, date.dayOfMonth)
        }

        fun of(date: java.time.LocalDate): YearMonthDay {
            return YearMonthDay(date.year, date.monthValue, date.dayOfMonth)
        }

        fun of(date: Instant): YearMonthDay {
            return of(date.toLocalDateTime(Marketplace.ServerTimeZone))
        }
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

    fun asIsoStringRange(): String {
        return "${start.asIsoString()} - ${end.asIsoString()}"
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

    companion object {
        fun currentWeek(): YearMonthDayRange {
            val firstDayOfWeek = WeekFields.of(Locale.getDefault()).firstDayOfWeek;
            val firstOfWeek = java.time.LocalDate.now(Marketplace.ServerTimeZone.toJavaZoneId())
                .with(TemporalAdjusters.previousOrSame(firstDayOfWeek))
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