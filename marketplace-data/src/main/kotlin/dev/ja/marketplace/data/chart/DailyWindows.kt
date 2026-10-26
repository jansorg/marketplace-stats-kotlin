/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.chart

import dev.ja.marketplace.client.Marketplace
import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.client.minus
import dev.ja.marketplace.client.model.PluginSale
import dev.ja.marketplace.client.plus
import dev.ja.marketplace.exchangeRate.ExchangeRates
import org.javamoney.moneta.FastMoney
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.ConcurrentHashMap
import javax.money.MonetaryAmount

/**
 * The values of a metric for each day.
 *
 * @param unit The currency code for amounts, `count` for numbers and `%` for percentages, see [ChartUnit.id].
 * @param values The value of each day of [DailyWindows.days], `null` if there is no value, e.g. no average without customers.
 * @param incompleteFrom Index of the first day, whose value may still change.
 * @param ratio The numerators and the denominators of rates.
 * @param partialUntil Index of the first day, whose window doesn't start before the first day with data, e.g. the sums
 *  of the first year of a plugin, which started in February, only cover a part of the year.
 */
data class DailySeries(
    val unit: String,
    val values: List<BigDecimal?>,
    val incompleteFrom: Int,
    val ratio: DailyRatio? = null,
    val partialUntil: Int = 0,
)

/**
 * The numerators and the denominators of the rates of a [DailySeries], e.g. the churned and the previously active
 * licenses, so that the rates of several windows can be pooled, see [ChartMetricValues.average].
 *
 * @param factor The factor of the rates, e.g. 100 for percentages.
 */
class DailyRatio(val numerators: List<BigDecimal?>, val denominators: List<BigDecimal?>, val factor: BigDecimal)

/**
 * The days of the values per day of the charts, from [first] until [today], and the windows of the sums and rates of
 * each day, see [ChartWindow]. Days before [first] aren't part of the windows.
 */
class DailyWindows(val first: YearMonthDay, val today: YearMonthDay) {
    val dayCount: Int = (first daysUntil today).toInt() + 1
    val days: List<YearMonthDay> = List(dayCount) { first.add(0, 0, it) }

    /** Index of the first day, whose sums may still change: today. */
    val incompleteSums: Int = dayCount - 1

    /** Index of the first day of the current month, the recurring revenue of the current month may still change. */
    val incompleteRecurringRevenue: Int = maxOf(0, index(YearMonthDay.firstOfMonth(today.year, today.month)))

    private val starts = ConcurrentHashMap<ChartWindow, IntArray>()

    /** @return The index of the first day of the window of each day. */
    fun starts(window: ChartWindow): IntArray {
        return starts.computeIfAbsent(window) {
            IntArray(dayCount) { index -> maxOf(0, index(window.start(days[index]))) }
        }
    }

    fun index(date: YearMonthDay): Int = (first daysUntil date).toInt()

    /** @return The sum of the values of each day, values of other days are ignored. */
    fun countsPerDay(values: Iterable<Pair<YearMonthDay, Long>>): LongArray {
        val perDay = LongArray(dayCount)
        for ((date, value) in values) {
            val index = index(date)
            if (index in 0 until dayCount) {
                perDay[index] += value
            }
        }
        return perDay
    }

    /**
     * @return The amounts of the sales and of their fees of each day, in the displayed currency of [exchangeRates].
     */
    fun salesAndFeesPerDay(sales: Iterable<PluginSale>, exchangeRates: ExchangeRates): Pair<Array<MonetaryAmount>, Array<MonetaryAmount>> {
        val zero: MonetaryAmount = FastMoney.zero(exchangeRates.targetCurrency)
        val totals = Array(dayCount) { zero }
        val fees = Array(dayCount) { zero }
        for (sale in sales) {
            val index = index(sale.date)
            if (index in 0 until dayCount) {
                totals[index] += exchangeRates.targetAmount(sale.date, sale.amountUSD, sale.amount)
                fees[index] += exchangeRates.targetAmount(
                    sale.date,
                    Marketplace.feeAmount(sale.date, sale.amountUSD),
                    Marketplace.feeAmount(sale.date, sale.amount),
                )
            }
        }
        return totals to fees
    }

    /** @return The sums of the values of the days in the window of each day, see [starts]. */
    fun windowSums(perDay: LongArray, starts: IntArray): List<Long> {
        return windowSums(starts, 0L, perDay::get, Long::plus, Long::minus)
    }

    /** @see windowSums */
    fun windowSums(perDay: Array<MonetaryAmount>, starts: IntArray): List<MonetaryAmount> {
        return windowSums(starts, perDay.first().multiply(0), perDay::get, MonetaryAmount::plus, MonetaryAmount::minus)
    }

    /** @see windowSums */
    fun windowSums(perDay: Array<BigDecimal>, starts: IntArray): List<BigDecimal> {
        return windowSums(starts, BigDecimal.ZERO, perDay::get, BigDecimal::add, BigDecimal::subtract)
    }

    /** @return The sum of the values of each day and of all days before it. */
    fun runningSums(perDay: Array<MonetaryAmount>): Array<MonetaryAmount> {
        val result = perDay.copyOf()
        for (index in 1 until result.size) {
            result[index] = result[index - 1] + perDay[index]
        }
        return result
    }

    private inline fun <T> windowSums(starts: IntArray, zero: T, perDay: (Int) -> T, plus: (T, T) -> T, minus: (T, T) -> T): List<T> {
        val running = ArrayList<T>(dayCount)
        var sum = zero
        for (index in 0 until dayCount) {
            sum = plus(sum, perDay(index))
            running += sum
        }
        return List(dayCount) { index ->
            val start = starts[index]
            if (start == 0) running[index] else minus(running[index], running[start - 1])
        }
    }

    companion object {
        /** @return The amounts with two decimal places, e.g. for a [DailySeries]. */
        fun amounts(values: List<MonetaryAmount>): List<BigDecimal?> {
            return values.map { it.number.numberValue(BigDecimal::class.java).setScale(2, RoundingMode.HALF_UP) }
        }
    }
}
