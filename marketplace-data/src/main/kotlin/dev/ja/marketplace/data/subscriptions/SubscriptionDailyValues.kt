/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.subscriptions

import dev.ja.marketplace.client.LicenseInfo
import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.client.model.CustomerType
import dev.ja.marketplace.data.PercentageValue
import dev.ja.marketplace.data.chart.ChartMetric
import dev.ja.marketplace.data.chart.ChartWindow
import dev.ja.marketplace.data.chart.DailyRatio
import dev.ja.marketplace.data.chart.DailySeries
import dev.ja.marketplace.data.trackers.RecurringRevenueSales
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap
import it.unimi.dsi.fastutil.ints.Int2ObjectMap
import it.unimi.dsi.fastutil.ints.Int2ObjectMaps
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * The values of the monthly tables of the subscription metrics for each day, for the charts, see
 * [dev.ja.marketplace.data.chart.ChartMetricValues].
 *
 * The values have the meaning and the scale of the values of the tables: the MRR of each customer at a day is compared
 * with the MRR at the day before the window of the day (MRR movements and retention), see [ChartWindow]. At the last day
 * of a month, the window of a month is compared with the last day of the previous month and the window of a year with
 * the last day of the same month of the previous year, so that the values of the last day of a month are the values of
 * the tables.
 *
 * The MRR of the customers at each day uses the rules of [CustomerRecurringRevenue] for the end of a month:
 * only the latest sale of each paid subscription license, which is valid at the day, contributes.
 */
object SubscriptionDailyValues {
    private val HUNDRED = BigDecimal(100)

    /** Days, whose MRR of the customers is kept to compare it, a year and a few days. */
    private const val KEPT_DAYS = 400

    /** The movements of the MRR between the start and the end of a window. */
    val windowMetrics: List<ChartMetric> = listOf(
        ChartMetric.MrrNew, ChartMetric.MrrExpansion, ChartMetric.MrrContraction, ChartMetric.MrrChurned,
        ChartMetric.MrrReactivation, ChartMetric.MrrNetChange, ChartMetric.QuickRatio,
    )

    /** The values at each day: the paying customers, their average revenue and the retention of a year. */
    val dayMetrics: List<ChartMetric> = listOf(
        ChartMetric.PreviousCustomers, ChartMetric.CustomerRetention, ChartMetric.GrossRevenueRetention,
        ChartMetric.NetRevenueRetention,
        ChartMetric.PayingCustomers, ChartMetric.AverageRevenue, ChartMetric.AverageRevenueOrganizations,
        ChartMetric.AverageRevenueIndividuals,
    )

    /**
     * @param days The days of the values, ordered and without gaps, up to [today].
     * @param licenses The licenses, which were used to create [revenue].
     * @param window The window of the movements of the MRR, or `null` for the values at each day, which don't depend
     *  on a window. The retention compares with the year before each day, like the table.
     * @return The values of the [windowMetrics], or of the [dayMetrics] without a window.
     */
    fun create(
        days: List<YearMonthDay>,
        licenses: Iterable<LicenseInfo>,
        revenue: CustomerRecurringRevenue,
        today: YearMonthDay = YearMonthDay.now(),
        window: ChartWindow?,
    ): Map<ChartMetric, DailySeries> {
        if (days.isEmpty()) {
            return emptyMap()
        }

        val first = days.first()
        val dayCount = days.size
        fun index(date: YearMonthDay): Int = (first daysUntil date).toInt()

        // the changes of the MRR of the customers at each day
        val changes = arrayOfNulls<Int2ObjectOpenHashMap<BigDecimal>>(dayCount + 1)
        val firstPaidIndices = Int2IntOpenHashMap().apply { defaultReturnValue(Int.MAX_VALUE) }
        fun addChange(index: Int, customer: Int, amount: BigDecimal) {
            changes[index] = (changes[index] ?: Int2ObjectOpenHashMap()).apply { merge(customer, amount, BigDecimal::add) }
        }

        val salesByLicense = licenses
            .filter(RecurringRevenueSales::isRecurring)
            .groupBy { it.id }
        for (licenseSales in salesByLicense.values) {
            // the latest sale only changes at the start or after the end of a sale
            val boundaries = licenseSales
                .flatMap { listOf(it.validity!!.start, it.validity!!.end.add(0, 0, 1)) }
                .distinct()
                .sorted()
            for ((segmentStart, nextStart) in boundaries.zipWithNext()) {
                // the first sale with the latest end, like CustomerRecurringRevenue
                val latest = RecurringRevenueSales.latest(licenseSales.filter { segmentStart in it.validity!! }) ?: continue
                val from = maxOf(0, index(segmentStart))
                val to = minOf(dayCount - 1, index(nextStart) - 1)
                if (from > to) {
                    continue
                }

                val customer = latest.sale.customer.code
                val mrr = revenue.monthlyAmount(latest)
                addChange(from, customer, mrr)
                addChange(to + 1, customer, mrr.negate())
                firstPaidIndices.put(customer, minOf(firstPaidIndices.get(customer), from))
            }
        }

        val metrics = if (window != null) windowMetrics else dayMetrics
        val columns = metrics.associateWith { MutableList<BigDecimal?>(dayCount) { null } }
        fun set(column: ChartMetric, index: Int, value: BigDecimal?) {
            columns.getValue(column)[index] = value
        }

        // the numerators and the denominators of the rates, e.g. to pool the rates of several months
        val ratioMetrics = mapOf(
            ChartMetric.QuickRatio to BigDecimal.ONE,
            ChartMetric.CustomerRetention to HUNDRED,
            ChartMetric.GrossRevenueRetention to HUNDRED,
            ChartMetric.NetRevenueRetention to HUNDRED,
        ).filterKeys { it in metrics }
        val numerators = ratioMetrics.mapValues { MutableList<BigDecimal?>(dayCount) { null } }
        val denominators = ratioMetrics.mapValues { MutableList<BigDecimal?>(dayCount) { null } }
        fun setRatio(metric: ChartMetric, index: Int, numerator: BigDecimal, denominator: BigDecimal) {
            numerators.getValue(metric)[index] = numerator
            denominators.getValue(metric)[index] = denominator
        }

        fun amount(value: BigDecimal?): BigDecimal? = value?.setScale(2, RoundingMode.HALF_UP)
        fun percentage(value: PercentageValue?): BigDecimal? = value?.value?.setScale(2, RoundingMode.HALF_UP)

        // retention needs a year of recurring revenue, like the table
        val firstRetentionDay = revenue.months.firstOrNull()

        // a customer is new in the first month, whose end a paid license covered, like in the table of the MRR movements.
        // The first day with recurring revenue is only used for customers, whose licenses didn't cover the end of a month.
        val firstPaidMonthDays = Int2IntOpenHashMap().apply { defaultReturnValue(Int.MAX_VALUE) }
        // nextInt() doesn't box the customers, the next() of a for loop is deprecated
        val customers = firstPaidIndices.keys.iterator()
        while (customers.hasNext()) {
            val customer = customers.nextInt()
            val month = revenue.firstPaidMonthIndex(customer)?.let(revenue.months::get) ?: continue
            firstPaidMonthDays.put(customer, index(minOf(YearMonthDay.lastOfMonth(month.year, month.month), today)))
        }
        fun firstPaidDay(customer: Int): Int = firstPaidMonthDays.get(customer).takeIf { it != Int.MAX_VALUE } ?: firstPaidIndices.get(customer)

        val running = Int2ObjectOpenHashMap<BigDecimal>()
        val snapshots = HashMap<Int, Int2ObjectMap<BigDecimal>>()
        var current: Int2ObjectMap<BigDecimal> = Int2ObjectMaps.emptyMap()
        for (index in 0 until dayCount) {
            val dayChanges = changes[index]
            if (dayChanges != null) {
                for (entry in dayChanges.int2ObjectEntrySet()) {
                    val mrr = running.get(entry.intKey)?.add(entry.value) ?: entry.value
                    if (mrr.signum() == 0) {
                        running.remove(entry.intKey)
                    } else {
                        running.put(entry.intKey, mrr)
                    }
                }
                current = Int2ObjectOpenHashMap(running)
            }
            snapshots[index] = current
            snapshots.remove(index - KEPT_DAYS)

            if (window != null) {
                // the MRR at the day before the window
                val previousIndex = index(window.start(days[index]).add(0, 0, -1))
                // the kept snapshots cover the longest window
                check(previousIndex < 0 || previousIndex >= index - KEPT_DAYS) { "The window $window is too long" }
                val previous = snapshots[previousIndex] ?: Int2ObjectMaps.emptyMap()
                val movements = MrrMovements.of(current, previous) { customer ->
                    firstPaidDay(customer) > previousIndex
                }
                set(ChartMetric.MrrNew, index, amount(movements.new))
                set(ChartMetric.MrrExpansion, index, amount(movements.expansion))
                set(ChartMetric.MrrContraction, index, amount(movements.contraction.negate()))
                set(ChartMetric.MrrChurned, index, amount(movements.churned.negate()))
                set(ChartMetric.MrrReactivation, index, amount(movements.reactivation))
                set(ChartMetric.MrrNetChange, index, amount(movements.netChange))
                set(ChartMetric.QuickRatio, index, movements.quickRatio)
                setRatio(ChartMetric.QuickRatio, index, movements.new + movements.expansion + movements.reactivation, movements.contraction + movements.churned)
                continue
            }

            val average = AverageRevenuePerCustomer.of(current, revenue::customerType)
            set(ChartMetric.PayingCustomers, index, BigDecimal.valueOf(average.customers.toLong()))
            set(ChartMetric.AverageRevenue, index, amount(average.average))
            set(ChartMetric.AverageRevenueOrganizations, index, amount(average.average(CustomerType.Organization)))
            set(ChartMetric.AverageRevenueIndividuals, index, amount(average.average(CustomerType.Individual)))

            // the retention compares with the MRR a year before the day, it needs a year of recurring revenue
            val previousYearIndex = index(ChartWindow.Year.start(days[index]).add(0, 0, -1))
            if (firstRetentionDay != null && previousYearIndex >= index(firstRetentionDay)) {
                val retention = RevenueRetention.of(current, snapshots[previousYearIndex] ?: Int2ObjectMaps.emptyMap())
                set(ChartMetric.PreviousCustomers, index, BigDecimal.valueOf(retention.previousCustomers.toLong()))
                set(ChartMetric.CustomerRetention, index, percentage(retention.customerRetention))
                set(ChartMetric.GrossRevenueRetention, index, percentage(retention.grossRevenueRetention))
                set(ChartMetric.NetRevenueRetention, index, percentage(retention.netRevenueRetention))
                setRatio(ChartMetric.CustomerRetention, index, BigDecimal.valueOf(retention.retainedCustomers.toLong()), BigDecimal.valueOf(retention.previousCustomers.toLong()))
                setRatio(ChartMetric.GrossRevenueRetention, index, retention.currentMrrWithoutExpansion, retention.previousMrr)
                setRatio(ChartMetric.NetRevenueRetention, index, retention.currentMrr, retention.previousMrr)
            }
        }

        // the values of the current month are incomplete like in the tables,
        // it's the value of today, it changes until the end of the month
        val incompleteFrom = maxOf(0, index(YearMonthDay.firstOfMonth(today.year, today.month)))
        val currency = revenue.currency.currencyCode
        return columns.mapValues { (metric, values) ->
            val ratio = ratioMetrics[metric]?.let { factor -> DailyRatio(numerators.getValue(metric), denominators.getValue(metric), factor) }
            DailySeries(metric.unit.id(currency), values, incompleteFrom, ratio)
        }
    }
}
