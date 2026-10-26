/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.chart

import dev.ja.marketplace.client.model.CustomerType
import dev.ja.marketplace.data.ChartMeasure

/** The unit of the values of a [ChartMetric]. */
enum class ChartUnit {
    /** Amounts in the displayed currency. */
    Amount,

    /** Numbers, e.g. of licenses. */
    Count,

    Percentage,

    /** Ratios like the quick ratio, they don't share a chart with counts. */
    Ratio;

    /** @return The unit of the charts: the currency code, `count`, `%` or `ratio`. */
    fun id(currency: String): String = when (this) {
        Amount -> currency
        Count -> "count"
        Percentage -> "%"
        Ratio -> "ratio"
    }
}

/**
 * A value, which the charts show over time, e.g. the sales or the MRR. The columns of the tables refer to it with
 * [dev.ja.marketplace.data.DataTableColumn.chartMetric], the same metric of different tables is the same series of
 * the charts. The values are calculated for each day by [ChartMetricValues], see `/chart-data`.
 *
 * @param id The ID of the metric, used by the charts and the stored setup of the charts.
 * @param title The name of the metric in the charts.
 * @param usesWindow If the values depend on the [ChartWindow], e.g. sums and rates. Values at each day like the MRR don't.
 */
class ChartMetric private constructor(
    val id: String,
    val title: String,
    val measure: ChartMeasure,
    val unit: ChartUnit,
    val usesWindow: Boolean = measure != ChartMeasure.Stock,
) {
    override fun toString(): String = id

    companion object {
        private val registered = LinkedHashMap<String, ChartMetric>()

        private fun register(id: String, title: String, measure: ChartMeasure, unit: ChartUnit, usesWindow: Boolean = measure != ChartMeasure.Stock): ChartMetric {
            val metric = ChartMetric(id, title, measure, unit, usesWindow)
            check(registered.put(id, metric) == null) { "Duplicate chart metric $id" }
            return metric
        }

        // the sales, downloads and trials
        val Sales = register("sales", "Sales", ChartMeasure.Flow, ChartUnit.Amount)
        val Fees = register("fees", "Fees", ChartMeasure.Flow, ChartUnit.Amount)
        val Invoice = register("invoice", "Invoice", ChartMeasure.Flow, ChartUnit.Amount)
        val Downloads = register("downloads", "Downloads", ChartMeasure.Flow, ChartUnit.Count)
        val Trials = register("trials", "Trials", ChartMeasure.Flow, ChartUnit.Count)
        val TrialConversion = register("trials-converted", "Trial conversion", ChartMeasure.Ratio, ChartUnit.Percentage)

        // the licenses and the recurring revenue at each day
        val Licenses = register("licenses", "Licenses", ChartMeasure.Stock, ChartUnit.Count)
        val PaidLicenses = register("licenses-paid", "Paid licenses", ChartMeasure.Stock, ChartUnit.Count)
        val Mrr = register("mrr", "MRR", ChartMeasure.Stock, ChartUnit.Amount)
        val Arr = register("arr", "ARR", ChartMeasure.Stock, ChartUnit.Amount)
        val ChurnAnnual = register("churn-annual", "Churn (annual)", ChartMeasure.Ratio, ChartUnit.Percentage)
        val ChurnMonthly = register("churn-monthly", "Churn (monthly)", ChartMeasure.Ratio, ChartUnit.Percentage)

        // the movements of the MRR between the start and the end of the window
        val MrrNew = register("mrr-new", "New MRR", ChartMeasure.Flow, ChartUnit.Amount)
        val MrrExpansion = register("mrr-expansion", "Expansion MRR", ChartMeasure.Flow, ChartUnit.Amount)
        val MrrContraction = register("mrr-contraction", "Contraction MRR", ChartMeasure.Flow, ChartUnit.Amount)
        val MrrChurned = register("mrr-churned", "Churned MRR", ChartMeasure.Flow, ChartUnit.Amount)
        val MrrReactivation = register("mrr-reactivation", "Reactivation MRR", ChartMeasure.Flow, ChartUnit.Amount)
        val MrrNetChange = register("mrr-net-change", "Net change of the MRR", ChartMeasure.Flow, ChartUnit.Amount)
        val QuickRatio = register("quick-ratio", "Quick ratio", ChartMeasure.Ratio, ChartUnit.Ratio)

        // the retention of the customers of a year before each day, like the table, independent of the window
        val PreviousCustomers = register("customers-year-ago", "Customers a year ago", ChartMeasure.Stock, ChartUnit.Count)
        val CustomerRetention = register("customer-retention", "Customer retention", ChartMeasure.Ratio, ChartUnit.Percentage, usesWindow = false)
        val GrossRevenueRetention = register("grr", "Gross revenue retention", ChartMeasure.Ratio, ChartUnit.Percentage, usesWindow = false)
        val NetRevenueRetention = register("nrr", "Net revenue retention", ChartMeasure.Ratio, ChartUnit.Percentage, usesWindow = false)

        // the paying customers at each day
        val PayingCustomers = register("paying-customers", "Paying customers", ChartMeasure.Stock, ChartUnit.Count)
        val AverageRevenue = register("arpa", "ARPA", ChartMeasure.Stock, ChartUnit.Amount)
        val AverageRevenueOrganizations = register("arpa-organizations", "ARPA of organizations", ChartMeasure.Stock, ChartUnit.Amount)
        val AverageRevenueIndividuals = register("arpa-individuals", "ARPA of individuals", ChartMeasure.Stock, ChartUnit.Amount)

        /**
         * The prefix of the shares of the sales of the customer types. The charts add the name of a row of the table,
         * e.g. `customer-type-share.Individual`, see [customerTypeShare].
         */
        const val CUSTOMER_TYPE_SHARE = "customer-type-share"

        private val customerTypeShares = CustomerType.entries.associateWith { type ->
            register("$CUSTOMER_TYPE_SHARE.$type", "Share of $type", ChartMeasure.Ratio, ChartUnit.Percentage)
        }

        /** @return The share of the sales of the [type] of customers. */
        fun customerTypeShare(type: CustomerType): ChartMetric = customerTypeShares.getValue(type)

        /** All metrics. */
        val all: Collection<ChartMetric> get() = registered.values

        fun byId(id: String): ChartMetric? = registered[id]
    }
}
