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
 * @param description What the metric measures, e.g. the tooltip of the metric in the menu to add metrics.
 * @param fractionDigits The maximum number of decimals of the shown values, e.g. of the moving averages. The values of
 *  `/chart-data` and of the exports aren't rounded, only the charts round them.
 * @param isPaidOnly If the metric needs the sales, licenses or trials of a paid or freemium plugin. Free plugins don't
 *  have them, their charts only offer the other metrics, e.g. the downloads.
 * @param family The family of a metric with parameters, e.g. the downloads of a release, see [ChartMetricFamily].
 * @param parameterValues The values of the parameters of the [family], `null` for missing optional values.
 */
class ChartMetric private constructor(
    val id: String,
    private val fixedTitle: String?,
    val measure: ChartMeasure,
    val unit: ChartUnit,
    val usesWindow: Boolean,
    val group: String,
    val description: String,
    val fractionDigits: Int,
    val isPaidOnly: Boolean,
    val family: ChartMetricFamily? = null,
    val parameterValues: List<String?> = emptyList(),
) {
    /** The windows, which have values, `null` for all windows, see [ChartMetricFamily.windows]. */
    val windows: Set<ChartWindow>? get() = family?.windows

    /** The title, a metric of a family has the labels of its values, e.g. the version of a release. */
    val title: String get() = fixedTitle ?: family!!.title(parameterValues)

    /** The name of the discount of a metric of [discount], `null` for the other metrics. */
    val discountName: String?
        get() = parameterValues.firstOrNull()?.takeIf { family == ChartMetricFamily.Discount }?.let(ChartMetricFamily::decodeName)

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
            description: String,
            fractionDigits: Int = defaultFractionDigits(unit, measure),
            isPaidOnly: Boolean = true,
        ): ChartMetric {
            val metric = ChartMetric(id, title, measure, unit, usesWindow, group, description, fractionDigits, isPaidOnly)
            check(registered.put(id, metric) == null) { "Duplicate chart metric $id" }
            return metric
        }

        /**
         * Counts at a date, e.g. of the active licenses, are whole numbers, also their averages, e.g. "1,235 paid
         * licenses". The averages of counts of a window keep a decimal, e.g. 3.5 trials per month or 0.4 per day.
         */
        private fun defaultFractionDigits(unit: ChartUnit, measure: ChartMeasure): Int = when {
            unit == ChartUnit.Count && measure == ChartMeasure.Stock -> 0
            unit == ChartUnit.Count -> 1
            else -> 2
        }

        // the sales, downloads and trials
        val Sales = register(
            "sales", "Sales before fees", ChartMeasure.Flow, ChartUnit.Amount, group = "Sales",
            description = "The sales of the plugin before the fees of the Marketplace",
        )
        val Fees = register(
            "fees", "Marketplace fees", ChartMeasure.Flow, ChartUnit.Amount, group = "Sales",
            description = "The fees of the Marketplace of the sales",
        )
        val Invoice = register(
            "invoice", "Paid amount", ChartMeasure.Flow, ChartUnit.Amount, group = "Sales",
            description = "The amount paid to the vendor: the sales minus the fees of the Marketplace",
        )
        val Downloads = register(
            "downloads", "Downloads", ChartMeasure.Flow, ChartUnit.Count, group = "Downloads",
            description = "The downloads of the plugin",
            isPaidOnly = false,
        )
        val Trials = register(
            "trials", "New trials", ChartMeasure.Flow, ChartUnit.Count, group = "Trials",
            description = "The started trials of the plugin",
        )
        val NewTrialUsers = register(
            "new-trial-users", "New trial users", ChartMeasure.Flow, ChartUnit.Count, group = "Trials",
            description = "The customers, who started their first trial of the plugin. Further trials of a customer aren't counted.",
        )
        val TrialConversion = register(
            "trials-converted", "Trial conversion rate", ChartMeasure.Ratio, ChartUnit.Percentage, group = "Trials",
            description = "The share of the trials started in the window, which were converted by a paid new license within the " +
                    "trial period and a few days after it",
        )

        // the licenses and the recurring revenue at each day
        val Licenses = register(
            "licenses", "Active licenses", ChartMeasure.Stock, ChartUnit.Count, group = "Licenses",
            description = "The licenses, which are valid at the date, paid and free",
        )
        val PaidLicenses = register(
            "licenses-paid", "Active paid licenses", ChartMeasure.Stock, ChartUnit.Count, group = "Licenses",
            description = "The paid licenses, which are valid at the date",
        )
        /** The rules are those of [ChartMetricValues.newLicenseDays]. */
        val NewLicenses = register(
            "new-licenses", "New licenses", ChartMeasure.Flow, ChartUnit.Count, group = "Licenses",
            description = "The paid licenses, which were bought for the first time. Renewals, free licenses and licenses, " +
                    "which another customer took over, aren't counted.",
        )
        val Mrr = register(
            "mrr", "MRR", ChartMeasure.Stock, ChartUnit.Amount, group = "Recurring revenue",
            description = "Monthly recurring revenue: the revenue of the valid subscriptions per month",
        )
        val Arr = register(
            "arr", "ARR", ChartMeasure.Stock, ChartUnit.Amount, group = "Recurring revenue",
            description = "Annual recurring revenue: the revenue of the valid subscriptions per year",
        )
        val ChurnAnnual = register(
            "churn-annual", "Churn rate of annual licenses", ChartMeasure.Ratio, ChartUnit.Percentage, group = "Churn",
            description = "The share of the paid annual licenses, which were active at the start of the window and aren't " +
                    "active at its end",
        )
        val ChurnMonthly = register(
            "churn-monthly", "Churn rate of monthly licenses", ChartMeasure.Ratio, ChartUnit.Percentage, group = "Churn",
            description = "The share of the paid monthly licenses, which were active at the start of the window and aren't " +
                    "active at its end",
        )

        /** The churn of the paid licenses of all periods in the window, see [dev.ja.marketplace.churn.isCountedByChurn]. */
        val ChurnCombined = register(
            "churn-combined", "Churn rate of all licenses", ChartMeasure.Ratio, ChartUnit.Percentage, group = "Churn",
            description = "Combined churn rate of the paid licenses of all license periods (annual, monthly, …): the share of " +
                    "the paid licenses, which were active at the start of the window and aren't active at its end. Annual " +
                    "licenses only churn at their renewal date.",
        )

        // the movements of the MRR between the start and the end of the window
        val MrrNew = register(
            "mrr-new", "New MRR", ChartMeasure.Flow, ChartUnit.Amount, group = "MRR movements",
            description = "The MRR of customers, who paid for the first time in the window",
        )
        val MrrExpansion = register(
            "mrr-expansion", "Expansion MRR", ChartMeasure.Flow, ChartUnit.Amount, group = "MRR movements",
            description = "The additional MRR of existing customers in the window, e.g. more licenses or a higher price",
        )
        val MrrContraction = register(
            "mrr-contraction", "Contraction MRR", ChartMeasure.Flow, ChartUnit.Amount, group = "MRR movements",
            description = "The lost MRR of remaining customers in the window, e.g. fewer licenses, a lower price or a higher " +
                    "discount",
        )
        val MrrChurned = register(
            "mrr-churned", "Churned MRR", ChartMeasure.Flow, ChartUnit.Amount, group = "MRR movements",
            description = "The MRR of the customers without recurring revenue at the end of the window",
        )
        val MrrReactivation = register(
            "mrr-reactivation", "Reactivation MRR", ChartMeasure.Flow, ChartUnit.Amount, group = "MRR movements",
            description = "The MRR of returning customers, who had recurring revenue before, but not at the start of the window",
        )
        val MrrNetChange = register(
            "mrr-net-change", "Net MRR change", ChartMeasure.Flow, ChartUnit.Amount, group = "MRR movements",
            description = "The change of the MRR in the window",
        )
        val QuickRatio = register(
            "quick-ratio", "Quick ratio", ChartMeasure.Ratio, ChartUnit.Ratio, group = "MRR movements",
            description = "(New + Expansion + Reactivation) / (Contraction + Churned) MRR of the window. Below 1 the MRR is " +
                    "shrinking.",
        )

        // the retention of the customers of a year before each day, like the table, independent of the window
        val PreviousCustomers = register(
            "customers-year-ago", "Paying customers a year ago", ChartMeasure.Stock, ChartUnit.Count, group = "Retention",
            description = "The customers with recurring revenue a year before the date",
        )
        val CustomerRetention = register(
            "customer-retention", "Customer retention", ChartMeasure.Ratio, ChartUnit.Percentage, group = "Retention", usesWindow = false,
            description = "The share of the customers with recurring revenue a year before the date, who still have recurring " +
                    "revenue",
        )
        val GrossRevenueRetention = register(
            "grr", "Gross revenue retention", ChartMeasure.Ratio, ChartUnit.Percentage, group = "Retention", usesWindow = false,
            description = "Gross revenue retention: the MRR of the customers of a year ago, without expansion, compared to " +
                    "their MRR a year ago. It's at most 100%.",
        )
        val NetRevenueRetention = register(
            "nrr", "Net revenue retention", ChartMeasure.Ratio, ChartUnit.Percentage, group = "Retention", usesWindow = false,
            description = "Net revenue retention: the MRR of the customers of a year ago, including expansion, compared to " +
                    "their MRR a year ago",
        )

        // the paying customers at each day
        val PayingCustomers = register(
            "paying-customers", "Paying customers", ChartMeasure.Stock, ChartUnit.Count, group = "Customers",
            description = "The customers with recurring revenue at the date",
        )
        /** The rules are those of [ChartMetricValues.newCustomerDays]. */
        val NewCustomers = register(
            "new-customers", "New customers", ChartMeasure.Flow, ChartUnit.Count, group = "Customers",
            description = "The customers, who bought their first paid license of the plugin. Returning customers, free " +
                    "licenses and customers, whose first license was transferred to them by another customer, aren't counted.",
        )
        val AverageRevenue = register(
            "arpa", "ARPA", ChartMeasure.Stock, ChartUnit.Amount, group = "Customers",
            description = "Average revenue per account: the average MRR per paying customer",
        )
        val AverageRevenueOrganizations = register(
            "arpa-organizations", "ARPA of organizations", ChartMeasure.Stock, ChartUnit.Amount, group = "Customers",
            description = "The average MRR per paying organization",
        )
        val AverageRevenueIndividuals = register(
            "arpa-individuals", "ARPA of individuals", ChartMeasure.Stock, ChartUnit.Amount, group = "Customers",
            description = "The average MRR per paying individual customer",
        )

        /**
         * The prefix of the shares of the sales of the customer types. The charts add the name of a row of the table,
         * e.g. `customer-type-share.Individual`, see [customerTypeShare].
         */
        val CUSTOMER_TYPE_SHARE: String get() = ChartMetricFamily.CustomerTypeShare.id

        /** @return The share of the sales of the [type] of customers. */
        fun customerTypeShare(type: CustomerType): ChartMetric = ChartMetricFamily.CustomerTypeShare.metric(listOf(type.toString()))

        /**
         * @return The amount given away by a discount, by the name of the offer or the description of a discount
         *  without an offer, see `OfferDiscounts.discountName`. Offers with the same name are one metric, see
         *  [ChartMetricFamily.Discount].
         */
        fun discount(discountName: String): ChartMetric {
            return ChartMetricFamily.Discount.metric(listOf(ChartMetricFamily.encodeName(discountName.trim())))
        }

        /** All metrics without parameters, the metrics with parameters belong to a [ChartMetricFamily]. */
        val all: Collection<ChartMetric> get() = registered.values

        /** @return The metric of the ID, also of a metric of a family, e.g. `release-downloads.1234`. */
        fun byId(id: String): ChartMetric? = registered[id] ?: ChartMetricFamily.parseMetric(id)

        /** @return A metric of the [family] with the [values] of its parameters, see [ChartMetricFamily.metric]. */
        internal fun ofFamily(id: String, family: ChartMetricFamily, values: List<String?>): ChartMetric {
            return ChartMetric(
                id, null, family.measure, family.unit, family.usesWindow, family.group, family.description, family.fractionDigits,
                family.isPaidOnly, family, values,
            )
        }
    }
}
