/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.chart

import dev.ja.marketplace.client.model.DownloadResponseItem
import dev.ja.marketplace.data.ChartMeasure
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * A parameter of a [ChartMetricFamily], e.g. the release of the downloads of a release.
 *
 * @param id The ID of the parameter, e.g. `release`.
 * @param title The name of the parameter in the charts, e.g. "Release".
 * @param allLabel The label of the missing value of an optional parameter, e.g. "All products". Only the last
 *  parameters of a family can be optional.
 * @param defaultLabel The label of a value, whose label wasn't registered, see [registerLabel].
 */
class ChartMetricParameter(
    val id: String,
    val title: String,
    val allLabel: String? = null,
    private val defaultLabel: (String) -> String = { it },
) {
    private val labels = ConcurrentHashMap<String, String>()

    val isOptional: Boolean get() = allLabel != null

    /** @return The label of a value, e.g. the version of a release, or the label of all values for `null`. */
    fun label(value: String?): String = when (value) {
        null -> allLabel ?: ""
        else -> labels[value] ?: defaultLabel(value)
    }

    /**
     * Registers the label of a value, e.g. the version of a release by its ID. The labels are shared by all plugins,
     * the values must be unique, e.g. the IDs of the releases.
     */
    fun registerLabel(value: String, label: String) {
        labels[value] = label
    }
}

/** A value of a [ChartMetricParameter], e.g. the ID and the version of a release. */
data class ChartParameterValue(val value: String, val label: String)

/**
 * Metrics with the same values for each value of their parameters, e.g. the downloads of each release, so that the
 * charts don't need a metric of each release. The ID of a metric of the family is the ID of the family followed by
 * its values, separated by `.`, e.g. `release-downloads.1234` or `release-downloads.1234.IIU`. Values must not contain
 * `.`, `,`, `:` or `@`, and missing optional values are left out.
 *
 * @param title The name of the family, e.g. in the menu to add metrics.
 * @param metricTitle The title of a metric of the family by the labels of its values, `null` for missing optional values.
 * @param isValidValue If a value of a parameter by its index is valid, e.g. a number for the ID of a release.
 * @param windows The windows, which have values, `null` for all windows, e.g. the unique downloads only exist for the
 *  periods of the statistics service. A family without parameters has a single metric with the ID of the family.
 */
class ChartMetricFamily internal constructor(
    val id: String,
    val title: String,
    val measure: ChartMeasure,
    val unit: ChartUnit,
    val group: String,
    val description: String,
    val parameters: List<ChartMetricParameter>,
    val isPaidOnly: Boolean,
    val fractionDigits: Int,
    private val metricTitle: (List<String?>) -> String,
    private val isValidValue: (index: Int, value: String) -> Boolean = { _, _ -> true },
    val windows: Set<ChartWindow>? = null,
) {
    val usesWindow: Boolean get() = measure != ChartMeasure.Stock

    private val metrics = ConcurrentHashMap<String, ChartMetric>()

    /**
     * @return The metric of the [values] of the parameters, `null` for a missing optional value.
     */
    fun metric(values: List<String?>): ChartMetric {
        require(values.size == parameters.size) { "The family $id needs ${parameters.size} values" }
        val present = values.dropLastWhile { it == null }
        require(present.none { it == null }) { "Only the last values of $id are optional" }
        parameters.forEachIndexed { index, parameter -> require(values[index] != null || parameter.isOptional) { "The parameter ${parameter.id} of $id is required" } }
        val metricId = (listOf(id) + present.map { it!! }).joinToString(".")
        return metrics.computeIfAbsent(metricId) { ChartMetric.ofFamily(it, this, values) }
    }

    /** @return The title of a metric of the family by its values. */
    fun title(values: List<String?>): String {
        return metricTitle(parameters.mapIndexed { index, parameter -> values[index]?.let(parameter::label) })
    }

    /** @return The metric of the ID, if it's a valid ID of the family, e.g. `release-downloads.1234`. */
    fun parse(metricId: String): ChartMetric? {
        if (parameters.isEmpty()) {
            return if (metricId == id) metric(emptyList()) else null
        }
        val values = metricId.removePrefix("$id.").takeIf { it != metricId }?.split('.') ?: return null
        val required = parameters.count { !it.isOptional }
        if (values.size !in required..parameters.size || values.withIndex().any { (index, value) -> value.isEmpty() || !isValidValue(index, value) }) {
            return null
        }
        return metric(values + List(parameters.size - values.size) { null })
    }

    override fun toString(): String = id

    companion object {
        private val registered = LinkedHashMap<String, ChartMetricFamily>()

        private fun register(family: ChartMetricFamily): ChartMetricFamily {
            check(registered.put(family.id, family) == null) { "Duplicate chart metric family ${family.id}" }
            return family
        }

        /** The share of the sales of each customer type, e.g. `customer-type-share.Individual`. */
        val CustomerTypeShare = register(
            ChartMetricFamily(
                "customer-type-share", "Share of sales of a customer type", ChartMeasure.Ratio, ChartUnit.Percentage, "Customer types",
                "The share of the sales of a customer type in the sales of the window",
                listOf(ChartMetricParameter("type", "Customer type")),
                isPaidOnly = true, fractionDigits = 2,
                metricTitle = { (type) -> "Share of sales: $type" },
            )
        )

        /**
         * The amount given away by a discount, by the name of the offer or the description of a discount without an
         * offer. The name is encoded as Base64 (URL-safe, without padding), e.g. `discount.U3VtbWVy`, because it may
         * contain `.`, `,` or `@`.
         */
        val Discount = register(
            ChartMetricFamily(
                "discount", "Discount amount", ChartMeasure.Flow, ChartUnit.Amount, "Discounts",
                "The amount given away by a discount in the sales of the window, calculated from the paid amount and the " +
                        "percent of the discount of each sale",
                listOf(ChartMetricParameter("discount", "Discount", defaultLabel = { decodeName(it) ?: it })),
                isPaidOnly = true, fractionDigits = 2,
                metricTitle = { (name) -> "Discount: $name" },
                isValidValue = { _, value -> decodeName(value)?.let(::encodeName) == value },
            )
        )

        /** The downloads of a release, e.g. `release-downloads.1234`, optionally only in a product, e.g. `release-downloads.1234.IIU`. */
        val ReleaseDownloads = register(
            ChartMetricFamily(
                "release-downloads", "Downloads of a release", ChartMeasure.Flow, ChartUnit.Count, "Downloads",
                "The downloads of a release of the plugin, of all products or of a single product",
                listOf(
                    ChartMetricParameter("release", "Release", defaultLabel = { "release $it" }),
                    ChartMetricParameter("product", "Product", allLabel = "All products"),
                ),
                isPaidOnly = false, fractionDigits = 1,
                // the title names both parameters, also all products
                metricTitle = { (release, product) -> "Downloads of $release in ${product ?: "all products"}" },
                isValidValue = { index, value -> if (index == 0) value.toIntOrNull() != null else isProductCode(value) },
            )
        )

        /** The downloads of the plugin in a product, by the code of the product, e.g. `product-downloads.IIU`. */
        val ProductDownloads = register(
            ChartMetricFamily(
                "product-downloads", "Downloads in a product", ChartMeasure.Flow, ChartUnit.Count, "Downloads",
                "The downloads of the plugin in a JetBrains product, e.g. IntelliJ IDEA",
                listOf(ChartMetricParameter("product", "Product")),
                isPaidOnly = false, fractionDigits = 1,
                metricTitle = { (product) -> "Downloads in $product" },
                isValidValue = { _, value -> isProductCode(value) },
            )
        )

        /**
         * The windows of the unique downloads: the statistics service has them by day, calendar week and calendar
         * month, they can't be summed, e.g. a user, who downloads on two days, is counted once in the month. At the
         * level Week, whose dates are Sundays, the window [ChartWindow.Week] is the calendar week.
         */
        val UNIQUE_DOWNLOADS_WINDOWS: Set<ChartWindow> = setOf(ChartWindow.Day, ChartWindow.Week, ChartWindow.CalendarMonth)

        /** The unique downloads of the plugin, e.g. `unique-downloads`. */
        val UniqueDownloads = register(
            ChartMetricFamily(
                "unique-downloads", "Unique downloads", ChartMeasure.Flow, ChartUnit.Count, "Downloads",
                "The unique downloads of the plugin in the window: a day, a calendar week or a calendar month",
                emptyList(), isPaidOnly = false, fractionDigits = 1,
                metricTitle = { "Unique downloads" },
                windows = UNIQUE_DOWNLOADS_WINDOWS,
            )
        )

        /** The unique downloads of a release, optionally in a product, e.g. `release-unique-downloads.1234.IIU`. */
        val ReleaseUniqueDownloads = register(
            ChartMetricFamily(
                "release-unique-downloads", "Unique downloads of a release", ChartMeasure.Flow, ChartUnit.Count, "Downloads",
                "The unique downloads of a release of the plugin in the window: a day, a calendar week or a calendar month",
                listOf(
                    ChartMetricParameter("release", "Release", defaultLabel = { "release $it" }),
                    ChartMetricParameter("product", "Product", allLabel = "All products"),
                ),
                isPaidOnly = false, fractionDigits = 1,
                metricTitle = { (release, product) -> "Unique downloads of $release in ${product ?: "all products"}" },
                isValidValue = { index, value -> if (index == 0) value.toIntOrNull() != null else isProductCode(value) },
                windows = UNIQUE_DOWNLOADS_WINDOWS,
            )
        )

        /** The unique downloads of the plugin in a product, e.g. `product-unique-downloads.IIU`. */
        val ProductUniqueDownloads = register(
            ChartMetricFamily(
                "product-unique-downloads", "Unique downloads in a product", ChartMeasure.Flow, ChartUnit.Count, "Downloads",
                "The unique downloads of the plugin in a JetBrains product in the window: a day, a calendar week or a calendar month",
                listOf(ChartMetricParameter("product", "Product")),
                isPaidOnly = false, fractionDigits = 1,
                metricTitle = { (product) -> "Unique downloads in $product" },
                isValidValue = { _, value -> isProductCode(value) },
                windows = UNIQUE_DOWNLOADS_WINDOWS,
            )
        )

        /** The codes of the products of the statistics of the Marketplace, e.g. `IIU`. */
        private val PRODUCT_CODE = Regex("[A-Za-z0-9_-]+")

        /**
         * @return If the downloads can be filtered by the product: the statistics service has no filter of the group
         *  of the downloads without a known product, see [DownloadResponseItem.UNKNOWN_PRODUCT].
         */
        fun isProductCode(value: String): Boolean = value.matches(PRODUCT_CODE) && value != DownloadResponseItem.UNKNOWN_PRODUCT

        val all: Collection<ChartMetricFamily> get() = registered.values

        fun byId(id: String): ChartMetricFamily? = registered[id]

        /** @return The metric of a family by its ID, e.g. `release-downloads.1234`. */
        fun parseMetric(metricId: String): ChartMetric? {
            return byId(metricId.substringBefore('.'))?.parse(metricId)
        }

        fun encodeName(name: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(name.toByteArray(Charsets.UTF_8))

        fun decodeName(encoded: String): String? {
            return try {
                String(Base64.getUrlDecoder().decode(encoded), Charsets.UTF_8)
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    }
}
