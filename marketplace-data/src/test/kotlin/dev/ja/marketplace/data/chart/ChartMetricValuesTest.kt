/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.chart

import dev.ja.marketplace.churn.ChurnValidities
import dev.ja.marketplace.churn.LicenseChurnProcessor
import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.client.YearMonthDayRange
import dev.ja.marketplace.client.model.DailyDownload
import dev.ja.marketplace.client.model.LicensePeriod
import dev.ja.marketplace.client.model.PluginSale
import dev.ja.marketplace.client.model.PluginSaleItemType
import dev.ja.marketplace.client.model.PluginTrial
import dev.ja.marketplace.client.model.SubscriptionPluginSaleItem
import dev.ja.marketplace.data.subscriptions.SubscriptionTestData.customer
import dev.ja.marketplace.data.subscriptions.SubscriptionTestData.inCurrency
import dev.ja.marketplace.data.subscriptions.SubscriptionTestData.licenses
import dev.ja.marketplace.data.subscriptions.SubscriptionTestData.sale
import dev.ja.marketplace.data.trackers.AnnualRecurringRevenueTracker
import dev.ja.marketplace.data.trackers.LicenseTracker
import dev.ja.marketplace.data.trackers.MonthlyRecurringRevenueTracker
import dev.ja.marketplace.data.trackers.PaymentAmountTracker
import dev.ja.marketplace.data.trackers.SimpleTrialTracker
import dev.ja.marketplace.data.trackers.getResultWithinConversionPeriod
import dev.ja.marketplace.data.trackers.trialConversionDays
import dev.ja.marketplace.client.Marketplace
import dev.ja.marketplace.exchangeRate.ExchangeRates
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.RoundingMode
import javax.money.MonetaryAmount
import kotlin.random.Random

class ChartMetricValuesTest {
    private val exchangeRates = ExchangeRates("USD")
    private val today = YearMonthDay(2025, 9, 17)

    /**
     * Monthly and annual licenses of several customers, with renewals, gaps and additional licenses.
     */
    private val sales: List<PluginSale> = run {
        val random = Random(42)
        (1..40).flatMap { customerId ->
            val customer = customer(customerId)
            val period = if (customerId % 3 == 0) LicensePeriod.Annual else LicensePeriod.Monthly
            var start = YearMonthDay(2023, 1 + random.nextInt(12), 1 + random.nextInt(28))
            val result = mutableListOf<PluginSale>()
            var type = PluginSaleItemType.New
            while (start <= today) {
                result += sale(customer, "license-$customerId", start, 5 + random.nextInt(50), period, type)
                // renewed directly or after a gap
                start = when (period) {
                    LicensePeriod.Annual -> start.add(1, 0, 0)
                    else -> start.add(0, 1, 0)
                }.add(0, 0, if (random.nextInt(5) == 0) random.nextInt(60) else 0)
                type = PluginSaleItemType.Renew
            }
            // an additional license of some customers
            if (customerId % 4 == 0) {
                result += sale(customer, "extra-$customerId", YearMonthDay(2024, 3, customerId % 28 + 1), 20, period)
            }
            result
        }.sortedBy { it.date }
    }

    private val licenses = licenses(*sales.toTypedArray())

    private val trials = (0 until 200).map { index ->
        PluginTrial("trial-$index", YearMonthDay(2023, 1, 1).add(0, 0, index * 5), customer(1000 + index))
    }

    private val downloads = (0..1000).map { DailyDownload(YearMonthDay(2023, 1, 1).add(0, 0, it), (it % 7).toLong()) }

    private val values = ChartMetricValues.create(sales, licenses, trials, downloads, exchangeRates, today)

    @Test
    fun daysFromTheFirstMonthUntilToday() {
        assertEquals(YearMonthDay(2023, 1, 1), values.days.first())
        assertEquals(today, values.days.last())
        assertTrue(ChartMetric.all.mapNotNull { values.series(it, ChartWindow.Month) }.all { it.values.size == values.days.size })
    }

    @Test
    suspend fun valuesAtTheEndOfEachMonthAreTheValuesOfTheTable() {
        var month = YearMonthDay(2023, 1, 1)
        while (month <= today) {
            val monthRange = YearMonthDayRange.ofMonth(month.year, month.month)
            if (monthRange.end > today) {
                break
            }
            val index = values.days.indexOf(monthRange.end)

            val licenseTracker = LicenseTracker<Unit>(monthRange)
            val mrrTracker = MonthlyRecurringRevenueTracker(monthRange, exchangeRates)
            val arrTracker = AnnualRecurringRevenueTracker(monthRange, exchangeRates)
            licenses.forEach {
                licenseTracker.add(Unit, it)
                mrrTracker.processLicenseSale(it)
                arrTracker.processLicenseSale(it)
            }
            val amounts = PaymentAmountTracker(monthRange, exchangeRates)
            sales.forEach { amounts.add(it.date, it.amountUSD, it.amount) }

            val label = "month ${monthRange.start}"
            assertEquals(licenseTracker.totalLicenseCount, value(ChartMetric.Licenses, index).toInt(), label)
            assertEquals(licenseTracker.paidLicensesCount, value(ChartMetric.PaidLicenses, index).toInt(), label)
            assertAmount(mrrTracker.getResult().amounts.getTotalAmount(), value(ChartMetric.Mrr, index), label)
            assertAmount(arrTracker.getResult().amounts.getTotalAmount(), value(ChartMetric.Arr, index), label)
            assertAmount(amounts.totalAmount, value(ChartMetric.Sales, index), label)
            assertAmount(amounts.paidAmount, value(ChartMetric.Invoice, index), label)

            val trialCount = trials.count { it.date in monthRange }
            assertEquals(trialCount, value(ChartMetric.Trials, index).toInt(), label)
            val downloadCount = downloads.filter { it.day in monthRange }.sumOf { it.downloads }
            assertEquals(downloadCount, value(ChartMetric.Downloads, index).toLong(), label)

            month = month.add(0, 1, 0)
        }
    }

    @Test
    fun churnIsTheChurnOfTheTable() {
        // licenses, which are renewed several times, sometimes late, and then end
        val random = Random(3)
        val churnSales = (1..80).flatMap { customerId ->
            val period = if (customerId % 2 == 0) LicensePeriod.Annual else LicensePeriod.Monthly
            var start = YearMonthDay(2023, 1, 1).add(0, 0, random.nextInt(600))
            (0..random.nextInt(if (period == LicensePeriod.Annual) 3 else 12)).map { renewal ->
                val sale = sale(customer(customerId), "churn-$customerId", start, 10, period, if (renewal == 0) PluginSaleItemType.New else PluginSaleItemType.Renew)
                start = sale.lineItems.single().let { (it as SubscriptionPluginSaleItem).subscriptionDates.end }
                    .add(0, 0, 1 + if (random.nextInt(4) == 0) random.nextInt(120) else 0)
                sale
            }
        }.filter { it.date <= today }
        val licenses = licenses(*churnSales.toTypedArray())
        val values = ChartMetricValues.create(churnSales, licenses, emptyList(), emptyList(), exchangeRates, today)
        val validities = ChurnValidities(licenses)
        // the churn of the table, like OverviewTable: the licenses active at the previous day and not active at the day
        fun expected(previous: YearMonthDay, day: YearMonthDay, period: LicensePeriod): BigDecimal? {
            val processor = LicenseChurnProcessor(previous, day)
            licenses.forEach { license ->
                validities.validity(license)?.let { processor.processValue(license, it, license.isPaidLicense && license.sale.licensePeriod == period) }
            }
            val result = processor.getResult(period)
            return if (result.previousActiveItemCount == 0) null else BigDecimal(result.churnRate * 100).setScale(2, RoundingMode.HALF_UP)
        }
        fun assertChurn(metric: ChartMetric, window: ChartWindow, period: LicensePeriod, previous: YearMonthDay, day: YearMonthDay) {
            val actual = values.series(metric, window)!!.values[values.days.indexOf(day)]
            assertEquals(expected(previous, day, period), actual, "$metric $window $day")
        }

        var month = YearMonthDay(2023, 1, 1)
        var churnedMonths = 0
        while (YearMonthDay.lastOfMonth(month.year, month.month) <= today) {
            val end = YearMonthDay.lastOfMonth(month.year, month.month)
            for ((column, period) in listOf(ChartMetric.ChurnAnnual to LicensePeriod.Annual, ChartMetric.ChurnMonthly to LicensePeriod.Monthly)) {
                assertChurn(column, ChartWindow.Month, period, month.add(0, 0, -1), end)
                // within a month, the churn of the month before the day
                assertChurn(column, ChartWindow.Month, period, month.add(0, 0, 14).add(0, -1, 0), month.add(0, 0, 14))
            }
            if ((values.series(ChartMetric.ChurnMonthly, ChartWindow.Month)!!.values[values.days.indexOf(end)] ?: BigDecimal.ZERO).signum() > 0) {
                churnedMonths++
            }
            month = month.add(0, 1, 0)
        }
        assertTrue(churnedMonths > 3, "months with churn: $churnedMonths")

        // the churn of the years
        assertChurn(ChartMetric.ChurnAnnual, ChartWindow.Year, LicensePeriod.Annual, YearMonthDay(2023, 12, 31), YearMonthDay(2024, 12, 31))
        assertChurn(ChartMetric.ChurnMonthly, ChartWindow.Year, LicensePeriod.Monthly, YearMonthDay(2023, 12, 31), YearMonthDay(2024, 12, 31))
        assertChurn(ChartMetric.ChurnMonthly, ChartWindow.Year, LicensePeriod.Monthly, today.add(-1, 0, 0), today)
        assertEquals(values.days.indexOf(YearMonthDay(2025, 1, 1)), values.series(ChartMetric.ChurnAnnual, ChartWindow.Year)!!.incompleteFrom)
        assertEquals(values.days.indexOf(YearMonthDay(2025, 9, 1)), values.series(ChartMetric.ChurnMonthly, ChartWindow.Month)!!.incompleteFrom)
    }

    @Test
    fun trialConversionIsTheConversionOfTheTable() {
        // trials, of which some are converted within the conversion period, some later and some not
        val random = Random(5)
        val trials = (1..300).map { index ->
            PluginTrial("trial-$index", YearMonthDay(2023, 1, 1).add(0, 0, random.nextInt(990)), customer(index))
        }
        val trialSales = trials.mapNotNull { trial ->
            when (random.nextInt(3)) {
                0 -> sale(trial.customer, "trial-license-${trial.referenceId}", trial.date.add(0, 0, random.nextInt(25)), 10)
                1 -> sale(trial.customer, "late-license-${trial.referenceId}", trial.date.add(0, 0, 60 + random.nextInt(30)), 10)
                else -> null
            }
        }.filter { it.date <= today }
        val values = ChartMetricValues.create(trialSales, licenses(*trialSales.toTypedArray()), trials, emptyList(), exchangeRates, today)

        // the conversion of the table
        val tracker = SimpleTrialTracker().apply { init(trials) }
        trialSales.forEach(tracker::processSale)
        fun expected(range: YearMonthDayRange): BigDecimal? {
            val result = tracker.getResultWithinConversionPeriod(range, Marketplace.MAX_TRIAL_DAYS_DEFAULT, today)
            return if (result.totalTrials == 0) null else result.convertedTrialsPercentage.value.setScale(2, RoundingMode.HALF_UP)
        }

        var month = YearMonthDay(2023, 1, 1)
        while (YearMonthDay.lastOfMonth(month.year, month.month) < today) {
            val range = YearMonthDayRange.ofMonth(month.year, month.month)
            val actual = values.series(ChartMetric.TrialConversion, ChartWindow.Month)!!.values[values.days.indexOf(range.end)]
            assertEquals(expected(range)?.stripTrailingZeros(), actual?.stripTrailingZeros(), "month ${range.start}")
            month = month.add(0, 1, 0)
        }
        val year = YearMonthDayRange.ofYear(2024)
        val actualYear = values.series(ChartMetric.TrialConversion, ChartWindow.Year)!!.values[values.days.indexOf(year.end)]
        assertEquals(expected(year)?.stripTrailingZeros(), actualYear?.stripTrailingZeros())

        // trials of the recent days can still convert
        val firstPending = trials.filter { trial -> trialSales.none { it.customer == trial.customer && it.date.daysUntil(trial.date) <= 0 && trial.date.daysUntil(it.date) <= trialConversionDays(Marketplace.MAX_TRIAL_DAYS_DEFAULT) } }
            .filter { it.date.daysUntil(today) <= trialConversionDays(Marketplace.MAX_TRIAL_DAYS_DEFAULT) }
            .minOf { it.date }
        assertEquals(values.days.indexOf(firstPending), values.series(ChartMetric.TrialConversion, ChartWindow.Month)!!.incompleteFrom)
    }

    @Test
    fun recurringRevenueOfTheCurrentMonthIsTheValueOfToday() {
        // the table measures the current month at today, like the licenses
        val currentMonth = YearMonthDayRange(YearMonthDay(today.year, today.month, 1), today)
        val mrrTracker = MonthlyRecurringRevenueTracker(currentMonth, exchangeRates)
        val arrTracker = AnnualRecurringRevenueTracker(currentMonth, exchangeRates)
        licenses.forEach {
            mrrTracker.processLicenseSale(it)
            arrTracker.processLicenseSale(it)
        }
        val index = values.days.indexOf(today)
        assertAmount(mrrTracker.getResult().amounts.getTotalAmount(), value(ChartMetric.Mrr, index), "MRR of today")
        assertAmount(arrTracker.getResult().amounts.getTotalAmount(), value(ChartMetric.Arr, index), "ARR of today")
    }

    @Test
    suspend fun sumsWithinAMonthAreTheSumsOfTheMonthBeforeTheDay() {
        val day = YearMonthDay(2024, 3, 15)
        val range = YearMonthDay(2024, 2, 16).rangeTo(day)
        val amounts = PaymentAmountTracker(range, exchangeRates)
        sales.forEach { amounts.add(it.date, it.amountUSD, it.amount) }

        val index = values.days.indexOf(day)
        assertAmount(amounts.totalAmount, value(ChartMetric.Sales, index), "sales")
        assertEquals(trials.count { it.date in range }, value(ChartMetric.Trials, index).toInt())
        assertEquals(downloads.filter { it.day in range }.sumOf { it.downloads }, value(ChartMetric.Downloads, index).toLong())
    }

    @Test
    fun amountsInTheDisplayedCurrency() {
        // paid in EUR, the displayed currency is EUR, the amounts aren't converted from USD
        val sales = listOf(
            inCurrency(sale(customer(1), "a", YearMonthDay(2025, 1, 1), 120, LicensePeriod.Annual), 100, "EUR"),
            inCurrency(sale(customer(2), "b", YearMonthDay(2025, 1, 1), 12), 9, "EUR"),
        )
        val values = ChartMetricValues.create(sales, licenses(*sales.toTypedArray()), emptyList(), emptyList(), ExchangeRates("EUR"), today)
        val index = values.days.indexOf(YearMonthDay(2025, 1, 31))

        val series = values.series(ChartMetric.Sales, ChartWindow.Month)!!
        assertEquals("EUR", series.unit)
        assertEquals(0, BigDecimal(109).compareTo(series.values[index]))
        assertEquals(0, BigDecimal("17.33").compareTo(values.series(ChartMetric.Mrr, ChartWindow.Month)!!.values[index]))
        assertEquals(0, BigDecimal(208).compareTo(values.series(ChartMetric.Arr, ChartWindow.Month)!!.values[index]))
    }

    @Test
    fun recurringRevenueUsesTheLatestSaleOfALicense() {
        val customer = customer(1)
        // the renewal overlaps the first sale, only the renewal is counted while both are valid
        val sales = listOf(
            sale(customer, "a", YearMonthDay(2025, 1, 1), 10),
            sale(customer, "a", YearMonthDay(2025, 1, 20), 30, type = PluginSaleItemType.Renew),
        )
        val values = ChartMetricValues.create(sales, licenses(*sales.toTypedArray()), emptyList(), emptyList(), exchangeRates, today)
        val mrr = values.series(ChartMetric.Mrr, ChartWindow.Month)!!.values
        assertEquals(0, BigDecimal(10).compareTo(mrr[values.days.indexOf(YearMonthDay(2025, 1, 19))]))
        assertEquals(0, BigDecimal(30).compareTo(mrr[values.days.indexOf(YearMonthDay(2025, 1, 20))]))
        assertEquals(0, BigDecimal(30).compareTo(mrr[values.days.indexOf(YearMonthDay(2025, 2, 19))]))
        assertEquals(0, BigDecimal.ZERO.compareTo(mrr[values.days.indexOf(YearMonthDay(2025, 2, 20))]))
        // the license is counted once
        val licenseCounts = values.series(ChartMetric.Licenses, ChartWindow.Month)!!.values
        assertEquals(0, BigDecimal.ONE.compareTo(licenseCounts[values.days.indexOf(YearMonthDay(2025, 1, 25))]))
    }

    @Test
    fun incompleteValues() {
        val lastIndex = values.days.lastIndex
        assertEquals(lastIndex, values.series(ChartMetric.Sales, ChartWindow.Month)!!.incompleteFrom)
        assertEquals(lastIndex, values.series(ChartMetric.Downloads, ChartWindow.Month)!!.incompleteFrom)
        // the recurring revenue of the current month is incomplete, like in the table
        assertEquals(values.days.indexOf(YearMonthDay(2025, 9, 1)), values.series(ChartMetric.Mrr, ChartWindow.Month)!!.incompleteFrom)
        assertEquals(values.days.size, values.series(ChartMetric.Licenses, ChartWindow.Month)!!.incompleteFrom)
    }

    @Test
    fun weeksEndOnSundaysAndToday() {
        val weekEnds = ChartDetail.Week.indices(values.days).map { values.days[it] }
        assertEquals(YearMonthDay(2023, 1, 1), weekEnds.first())
        assertEquals(YearMonthDay(2023, 1, 8), weekEnds[1])
        assertEquals(today, weekEnds.last())
    }

    @Test
    fun noData() {
        val values = ChartMetricValues.create(emptyList(), emptyList(), emptyList(), emptyList(), exchangeRates, today)
        assertTrue(values.days.isEmpty())
        assertTrue(ChartMetric.all.all { values.series(it, ChartWindow.Month) == null })
    }

    private fun value(metric: ChartMetric, index: Int): BigDecimal {
        return values.series(metric, ChartWindow.Month)!!.values[index]!!
    }

    private fun assertAmount(expected: MonetaryAmount, actual: BigDecimal, message: String) {
        val expectedValue = expected.number.numberValue(BigDecimal::class.java).setScale(2, RoundingMode.HALF_UP)
        assertEquals(0, expectedValue.compareTo(actual), "$message: expected $expectedValue, got $actual")
    }
}
