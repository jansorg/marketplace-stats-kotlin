/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.subscriptions

import dev.ja.marketplace.client.LicenseInfo
import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.client.model.CustomerType
import dev.ja.marketplace.data.PercentageValue
import dev.ja.marketplace.data.overview.DailySeries
import dev.ja.marketplace.data.trackers.RecurringRevenueSales
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap
import it.unimi.dsi.fastutil.ints.Int2ObjectMap
import it.unimi.dsi.fastutil.ints.Int2ObjectMaps
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * The columns of the monthly tables of the subscription metrics, which have values for each day.
 * The [id] is used by the charts.
 */
enum class SubscriptionDailyColumn(val id: String) {
    Mrr("subscriptions-mrr"),
    New("mrr-new"),
    Expansion("mrr-expansion"),
    Contraction("mrr-contraction"),
    Churned("mrr-churned"),
    Reactivation("mrr-reactivation"),
    NetChange("mrr-net-change"),

    // the MRR movements of the year before each day, between the MRR a year before the day and at the day
    NewOfYear("mrr-new.year"),
    ExpansionOfYear("mrr-expansion.year"),
    ContractionOfYear("mrr-contraction.year"),
    ChurnedOfYear("mrr-churned.year"),
    ReactivationOfYear("mrr-reactivation.year"),
    NetChangeOfYear("mrr-net-change.year"),

    CustomersYearAgo("customers-year-ago"),
    CustomerRetention("customer-retention"),
    GrossRevenueRetention("grr"),
    NetRevenueRetention("nrr"),
    PayingCustomers("paying-customers"),
    AverageRevenue("arpa"),
    AverageRevenueOrganizations("arpa-organizations"),
    AverageRevenueIndividuals("arpa-individuals"),
}

/**
 * The values of the monthly tables of the subscription metrics for each day, for charts with a finer resolution than
 * the months of the tables.
 *
 * The values have the meaning and the scale of the values of the tables, like
 * [dev.ja.marketplace.data.overview.OverviewDailyValues]: the MRR of each customer at a day is compared with the MRR
 * one month before the day (MRR movements) or one year before the day (revenue retention). At the last day of a month,
 * it's compared with the last day of the previous month or of the same month of the previous year,
 * so that the values of the last day of a month are the values of the tables.
 *
 * The MRR of the customers at each day uses the rules of [CustomerRecurringRevenue] for the end of a month:
 * only the latest sale of each paid subscription license, which is valid at the day, contributes.
 */
object SubscriptionDailyValues {
    /** Days, whose MRR of the customers is kept to compare it, a year and a few days. */
    private const val KEPT_DAYS = 400

    /**
     * @param days The days of the values, ordered and without gaps, up to [today].
     * @param licenses The licenses, which were used to create [revenue].
     */
    fun create(
        days: List<YearMonthDay>,
        licenses: Iterable<LicenseInfo>,
        revenue: CustomerRecurringRevenue,
        today: YearMonthDay = YearMonthDay.now(),
    ): Map<SubscriptionDailyColumn, DailySeries> {
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

        val columns = SubscriptionDailyColumn.entries.associateWith { MutableList<BigDecimal?>(dayCount) { null } }
        fun set(column: SubscriptionDailyColumn, index: Int, value: BigDecimal?) {
            columns.getValue(column)[index] = value
        }

        fun amount(value: BigDecimal?): BigDecimal? = value?.setScale(2, RoundingMode.HALF_UP)
        fun percentage(value: PercentageValue?): BigDecimal? = value?.value?.setScale(2, RoundingMode.HALF_UP)

        // retention needs a year of recurring revenue, like the table
        val firstRetentionDay = revenue.months.firstOrNull()

        // a customer is new in the first month, whose end a paid license covered, like in the table of the MRR movements.
        // The first day with recurring revenue is only used for customers, whose licenses didn't cover the end of a month.
        val firstPaidMonthDays = Int2IntOpenHashMap().apply { defaultReturnValue(Int.MAX_VALUE) }
        for (customer in firstPaidIndices.keys) {
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

            val day = days[index]
            val isLastOfMonth = day == YearMonthDay.lastOfMonth(day.year, day.month)

            val previousMonthDay = when {
                isLastOfMonth -> day.add(0, -1, 0).let { YearMonthDay.lastOfMonth(it.year, it.month) }
                else -> day.add(0, -1, 0)
            }
            val previousMonthIndex = index(previousMonthDay)
            val previousMonth = snapshots[previousMonthIndex] ?: Int2ObjectMaps.emptyMap()
            val movements = MrrMovements.of(current, previousMonth) { customer ->
                firstPaidDay(customer) > previousMonthIndex
            }
            set(SubscriptionDailyColumn.Mrr, index, amount(movements.mrr))
            set(SubscriptionDailyColumn.New, index, amount(movements.new))
            set(SubscriptionDailyColumn.Expansion, index, amount(movements.expansion))
            set(SubscriptionDailyColumn.Contraction, index, amount(movements.contraction.negate()))
            set(SubscriptionDailyColumn.Churned, index, amount(movements.churned.negate()))
            set(SubscriptionDailyColumn.Reactivation, index, amount(movements.reactivation))
            set(SubscriptionDailyColumn.NetChange, index, amount(movements.netChange))

            val average = AverageRevenuePerCustomer.of(current, revenue::customerType)
            set(SubscriptionDailyColumn.PayingCustomers, index, BigDecimal.valueOf(average.customers.toLong()))
            set(SubscriptionDailyColumn.AverageRevenue, index, amount(average.average))
            set(SubscriptionDailyColumn.AverageRevenueOrganizations, index, amount(average.average(CustomerType.Organization)))
            set(SubscriptionDailyColumn.AverageRevenueIndividuals, index, amount(average.average(CustomerType.Individual)))

            val previousYearDay = when {
                isLastOfMonth -> YearMonthDay.lastOfMonth(day.year - 1, day.month)
                else -> day.add(-1, 0, 0)
            }
            val previousYearIndex = index(previousYearDay)
            val previousYear = snapshots[previousYearIndex] ?: Int2ObjectMaps.emptyMap()
            val movementsOfYear = MrrMovements.of(current, previousYear) { customer ->
                firstPaidDay(customer) > previousYearIndex
            }
            set(SubscriptionDailyColumn.NewOfYear, index, amount(movementsOfYear.new))
            set(SubscriptionDailyColumn.ExpansionOfYear, index, amount(movementsOfYear.expansion))
            set(SubscriptionDailyColumn.ContractionOfYear, index, amount(movementsOfYear.contraction.negate()))
            set(SubscriptionDailyColumn.ChurnedOfYear, index, amount(movementsOfYear.churned.negate()))
            set(SubscriptionDailyColumn.ReactivationOfYear, index, amount(movementsOfYear.reactivation))
            set(SubscriptionDailyColumn.NetChangeOfYear, index, amount(movementsOfYear.netChange))

            if (firstRetentionDay != null && previousYearDay >= firstRetentionDay) {
                val retention = RevenueRetention.of(current, previousYear)
                set(SubscriptionDailyColumn.CustomersYearAgo, index, BigDecimal.valueOf(retention.previousCustomers.toLong()))
                set(SubscriptionDailyColumn.CustomerRetention, index, percentage(retention.customerRetention))
                set(SubscriptionDailyColumn.GrossRevenueRetention, index, percentage(retention.grossRevenueRetention))
                set(SubscriptionDailyColumn.NetRevenueRetention, index, percentage(retention.netRevenueRetention))
            }
        }

        // the values of the current month are incomplete like in the tables,
        // it's the value of today, it changes until the end of the month
        val incompleteFrom = maxOf(0, index(YearMonthDay.firstOfMonth(today.year, today.month)))
        val currency = revenue.currency.currencyCode
        return columns.mapValues { (column, values) ->
            val unit = when (column) {
                SubscriptionDailyColumn.CustomersYearAgo, SubscriptionDailyColumn.PayingCustomers -> "count"
                SubscriptionDailyColumn.CustomerRetention, SubscriptionDailyColumn.GrossRevenueRetention,
                SubscriptionDailyColumn.NetRevenueRetention -> "%"

                else -> currency
            }
            DailySeries(unit, values, incompleteFrom)
        }
    }
}
