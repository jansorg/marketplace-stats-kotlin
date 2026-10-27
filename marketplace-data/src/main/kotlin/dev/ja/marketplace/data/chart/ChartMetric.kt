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
 * @param title The name of the metric in the charts. It describes the values without the table, e.g. "Paid amount"
 *  instead of the column "Invoice" of a table of sales.
 * @param usesWindow If the values depend on the [ChartWindow], e.g. sums and rates. Values at each day like the MRR don't.
 * @param group The kind of the metric, e.g. "Trials", the menu to add metrics groups them.
 */
class ChartMetric private constructor(
    val id: String,
    val title: String,
    val measure: ChartMeasure,
    val unit: ChartUnit,
    val usesWindow: Boolean,
    val group: String,
) {
    override fun toString(): String = id

    companion object {
        private val registered = LinkedHashMap<String, ChartMetric>()

        private fun register(
            id: String,
            title: String,
            measure: ChartMeasure,
            unit: ChartUnit,
            usesWindow: Boolean = measure != ChartMeasure.Stock,
            group: String,
        ): ChartMetric {
            val metric = ChartMetric(id, title, measure, unit, usesWindow, group)
            check(registered.put(id, metric) == null) { "Duplicate chart metric $id" }
            return metric
        }

        // the sales, downloads and trials
        val Sales = register("sales", "Sales before fees", ChartMeasure.Flow, ChartUnit.Amount, group = "Sales")
        val Fees = register("fees", "Marketplace fees", ChartMeasure.Flow, ChartUnit.Amount, group = "Sales")
        val Invoice = register("invoice", "Paid amount", ChartMeasure.Flow, ChartUnit.Amount, group = "Sales")
        val Downloads = register("downloads", "Downloads", ChartMeasure.Flow, ChartUnit.Count, group = "Downloads")
        val Trials = register("trials", "New trials", ChartMeasure.Flow, ChartUnit.Count, group = "Trials")
        val TrialConversion = register("trials-converted", "Trial conversion rate", ChartMeasure.Ratio, ChartUnit.Percentage, group = "Trials")

        // the licenses and the recurring revenue at each day
        val Licenses = register("licenses", "Active licenses", ChartMeasure.Stock, ChartUnit.Count, group = "Licenses")
        val PaidLicenses = register("licenses-paid", "Active paid licenses", ChartMeasure.Stock, ChartUnit.Count, group = "Licenses")
        val Mrr = register("mrr", "MRR", ChartMeasure.Stock, ChartUnit.Amount, group = "Recurring revenue")
        val Arr = register("arr", "ARR", ChartMeasure.Stock, ChartUnit.Amount, group = "Recurring revenue")
        val ChurnAnnual = register("churn-annual", "Churn rate of annual licenses", ChartMeasure.Ratio, ChartUnit.Percentage, group = "Churn")
        val ChurnMonthly = register("churn-monthly", "Churn rate of monthly licenses", ChartMeasure.Ratio, ChartUnit.Percentage, group = "Churn")

        /** The churn of the paid licenses of all periods in the window, see [dev.ja.marketplace.churn.isCountedByChurn]. */
        val ChurnCombined = register("churn-combined", "Churn rate of all licenses", ChartMeasure.Ratio, ChartUnit.Percentage, group = "Churn")

        // the movements of the MRR between the start and the end of the window
        val MrrNew = register("mrr-new", "New MRR", ChartMeasure.Flow, ChartUnit.Amount, group = "MRR movements")
        val MrrExpansion = register("mrr-expansion", "Expansion MRR", ChartMeasure.Flow, ChartUnit.Amount, group = "MRR movements")
        val MrrContraction = register("mrr-contraction", "Contraction MRR", ChartMeasure.Flow, ChartUnit.Amount, group = "MRR movements")
        val MrrChurned = register("mrr-churned", "Churned MRR", ChartMeasure.Flow, ChartUnit.Amount, group = "MRR movements")
        val MrrReactivation = register("mrr-reactivation", "Reactivation MRR", ChartMeasure.Flow, ChartUnit.Amount, group = "MRR movements")
        val MrrNetChange = register("mrr-net-change", "Net MRR change", ChartMeasure.Flow, ChartUnit.Amount, group = "MRR movements")
        val QuickRatio = register("quick-ratio", "Quick ratio", ChartMeasure.Ratio, ChartUnit.Ratio, group = "MRR movements")

        // the retention of the customers of a year before each day, like the table, independent of the window
        val PreviousCustomers = register("customers-year-ago", "Paying customers a year ago", ChartMeasure.Stock, ChartUnit.Count, group = "Retention")
        val CustomerRetention = register("customer-retention", "Customer retention", ChartMeasure.Ratio, ChartUnit.Percentage, group = "Retention", usesWindow = false)
        val GrossRevenueRetention = register("grr", "Gross revenue retention", ChartMeasure.Ratio, ChartUnit.Percentage, group = "Retention", usesWindow = false)
        val NetRevenueRetention = register("nrr", "Net revenue retention", ChartMeasure.Ratio, ChartUnit.Percentage, group = "Retention", usesWindow = false)

        // the paying customers at each day
        val PayingCustomers = register("paying-customers", "Paying customers", ChartMeasure.Stock, ChartUnit.Count, group = "Customers")
        val AverageRevenue = register("arpa", "ARPA", ChartMeasure.Stock, ChartUnit.Amount, group = "Customers")
        val AverageRevenueOrganizations = register("arpa-organizations", "ARPA of organizations", ChartMeasure.Stock, ChartUnit.Amount, group = "Customers")
        val AverageRevenueIndividuals = register("arpa-individuals", "ARPA of individuals", ChartMeasure.Stock, ChartUnit.Amount, group = "Customers")

        /**
         * The prefix of the shares of the sales of the customer types. The charts add the name of a row of the table,
         * e.g. `customer-type-share.Individual`, see [customerTypeShare].
         */
        const val CUSTOMER_TYPE_SHARE = "customer-type-share"

        private val customerTypeShares = CustomerType.entries.associateWith { type ->
            register("$CUSTOMER_TYPE_SHARE.$type", "Share of sales: $type", ChartMeasure.Ratio, ChartUnit.Percentage, group = "Customer types")
        }

        /** @return The share of the sales of the [type] of customers. */
        fun customerTypeShare(type: CustomerType): ChartMetric = customerTypeShares.getValue(type)

        /** All metrics. */
        val all: Collection<ChartMetric> get() = registered.values

        fun byId(id: String): ChartMetric? = registered[id]
    }
}
