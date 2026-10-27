/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.offers

import dev.ja.marketplace.client.LicenseInfo
import dev.ja.marketplace.client.MarketplaceClient
import dev.ja.marketplace.client.PluginId
import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.client.model.PluginOffer
import dev.ja.marketplace.client.model.PluginOfferCoupon
import dev.ja.marketplace.client.model.PluginOfferId
import dev.ja.marketplace.data.*
import dev.ja.marketplace.data.chart.ChartMetric
import dev.ja.marketplace.data.trackers.MonetaryAmountTracker
import dev.ja.marketplace.util.sortValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.math.BigDecimal
import java.math.RoundingMode

/** The tables of the offers page. */
enum class OffersTableKind(val title: String, val id: String) {
    /**
     * The discounts of the Marketplace, which aren't offers and have no dates: the continuity discounts of renewals
     * and the specials, e.g. for students or startups and the volume discounts of subscriptions, see
     * [OfferUsages.continuityDiscounts] and [OfferUsages.marketplaceSpecials].
     */
    Marketplace("Marketplace", "offers-marketplace"),

    /**
     * The offers with a section of the active offers, which haven't ended and have an active coupon, and of the other
     * offers and discounts, which were used in the last [OffersTable.RECENT_USE_DAYS] days, and a section of the
     * inactive offers, also if they were used recently, and of the other offers and discounts. The sections share the
     * widths of the columns.
     */
    Offers("Offers", "offers"),
}

class OffersTableFactory(private val kind: OffersTableKind) : MarketplaceDataTableFactory {
    override fun createTable(client: MarketplaceClient, maxTableRows: Int?): DataTable {
        return OffersTable(kind, OfferSource.of(client))
    }
}

/** Loads the offers of a plugin and their coupons, e.g. with the client or the data provider of the MCP server. */
interface OfferSource {
    suspend fun offers(pluginId: PluginId): List<PluginOffer>

    suspend fun offerCoupons(pluginId: PluginId, offer: PluginOfferId): List<PluginOfferCoupon>

    companion object {
        fun of(client: MarketplaceClient): OfferSource = object : OfferSource {
            override suspend fun offers(pluginId: PluginId): List<PluginOffer> = client.offers(pluginId)

            override suspend fun offerCoupons(pluginId: PluginId, offer: PluginOfferId): List<PluginOfferCoupon> {
                return client.offerCoupons(pluginId, offer)
            }
        }
    }
}

/**
 * How the offers of a plugin were used by its licenses, see [OfferUsages] for how a sale is matched to an offer.
 * The offers and their coupons are loaded when the table is calculated.
 */
class OffersTable(
    private val kind: OffersTableKind,
    private val source: OfferSource,
    private val today: YearMonthDay = YearMonthDay.now(),
) : SimpleDataTable(
    kind.title, kind.id,
    // the sections of the offers have their own header rows, which can't be sorted
    if (kind == OffersTableKind.Offers) "table-column-wide" else "table-column-wide sortable",
), MarketplaceDataSink {
    /** The title of the only section of the offers, e.g. "Active" without inactive offers. */
    private var singleSectionTitle: String? = null

    override val title: String
        get() = singleSectionTitle ?: kind.title

    private var offers: List<OfferWithCoupons> = emptyList()
    private var pluginId: PluginId? = null
    private var pluginName: String = ""
    private lateinit var usages: OfferUsages


    // the title of the table is shown in the header of the first column
    private val columnName = DataTableColumn(
        "offer-name", null,
        // the names take the free width of the table, the other columns don't wrap. The minimum width keeps the names
        // readable in narrow windows, the table scrolls then.
        cssStyle = "width: 100%; min-width: 16rem",
        tooltip = "The name of the offer or the description of the discount. The sales don't contain the offer, a " +
                "discount described as \"<name>\" offer is counted for the offer with this name, a renamed offer " +
                "doesn't match its earlier sales.",
        accessibleTitle = "Offer",
    )
    private val columnDiscount = DataTableColumn(
        "offer-discount", "Discount", "nowrap",
        tooltip = "The discount of the offer in percent, or its free months. Discounts without an offer of the plugin " +
                "show the rounded percents of the sales, they vary a little with the currency of the sale.",
    )
    private val columnDates = DataTableColumn(
        // left-aligned, so that the start dates line up, also without an end
        "offer-dates", "Dates", "nowrap",
        tooltip = "The first and the last day of the offer, without an end if it doesn't end",
    )
    private val columnLicenses = DataTableColumn(
        "offer-licenses", "Licenses", "num",
        tooltip = "The purchases of licenses with the discount, a license renewed with the discount is counted for each renewal",
    )
    private val columnSales = DataTableColumn(
        "offer-sales", "Sales", "num", tooltip = "The number of sales with the discount, linked to the licenses with the discount",
    )
    private val columnCustomers = DataTableColumn(
        "offer-customers", "Customers", "num", tooltip = "The number of customers with the discount, linked to the customers",
    )
    private val columnPaid = DataTableColumn("offer-paid", "Paid", "num", tooltip = "The amount paid by the licenses, after all discounts")
    private val columnLost = DataTableColumn(
        "offer-discount-amount", "Discount Amount", "num",
        tooltip = "The amount given away by the discount: the amount before the discount minus the paid amount. " +
                "It's calculated from the paid amount and the percent of the discount of each sale, the discounts of a sale are " +
                "applied one after the other. Licenses with a discount of 100 % or an unknown percent paid nothing, " +
                "they aren't included.",
    )
    private val columnFirstUse = DataTableColumn("offer-first-use", "First Use", "date", tooltip = "The date of the first sale with the discount")
    private val columnLastUse = DataTableColumn(
        "offer-last-use", "Last Use", "date", tooltip = "The date of the latest sale with the discount",
        preSorted = AriaSortOrder.Descending,
    )

    private val usageColumns = listOf(
        columnLicenses,
        columnSales,
        columnCustomers,
        columnPaid,
        columnLost,
        columnFirstUse,
        columnLastUse,
    )

    override val columns: List<DataTableColumn> = when (kind) {
        // the names contain the percents, the discounts have no dates
        OffersTableKind.Marketplace -> listOf(columnName, columnLicenses, columnSales, columnCustomers, columnPaid, columnLost)
        else -> listOf(columnName, columnDiscount, columnDates) + usageColumns
    }

    override suspend fun init(data: PluginData) {
        super.init(data)
        pluginId = data.pluginId
        pluginName = data.pluginSummary.name

        if (data.pluginSummary.isPaidOrFreemium) {
            val pluginId = data.pluginId
            val pluginOffers = source.offers(pluginId)
            offers = coroutineScope {
                pluginOffers.map { offer -> async { OfferWithCoupons(offer, loadCoupons(offer)) } }.awaitAll()
            }
        }
        usages = OfferUsages(offers.map { it.offer }, exchangeRates)
    }

    private suspend fun loadCoupons(offer: PluginOffer): List<PluginOfferCoupon>? {
        return try {
            source.offerCoupons(offer.pluginId, offer.id)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // the coupons are optional, the offer is then active by its dates
            null
        }
    }

    override suspend fun process(licenseInfo: LicenseInfo) {
        usages.add(licenseInfo)
    }

    override suspend fun createSections(): List<DataTableSection> {
        if (kind == OffersTableKind.Marketplace) {
            // the continuity discounts by their percent, then the specials by their use
            val continuity = usages.continuityDiscounts.entries
                .sortedBy { it.value.percents.firstOrNull() ?: Double.MAX_VALUE }
                .map { (name, usage) -> otherRow(name, usage) }
            val specials = usages.marketplaceSpecials.entries
                .sortedByDescending { it.value.licenses }
                .map { (name, usage) -> otherRow(name, usage, nameWithPercents(name, usage)) }
            val rows = continuity + specials
            return listOf(SimpleTableSection(rows.map { it.first }, footer = footer(rows.mapNotNull { it.second })))
        }

        val sections = listOf(true to "Active", false to "Inactive").mapNotNull { (isActive, title) ->
            val rows = offerRows(isActive)
            if (rows.isEmpty()) null else SimpleTableSection(rows.map { it.first }, title = title, footer = footer(rows.mapNotNull { it.second }))
        }
        // a single section is shown with the title of the table
        singleSectionTitle = sections.singleOrNull()?.title
        return sections
    }

    /** @return The rows of the active or inactive offers and of the other offers and discounts, the latest use first. */
    private fun offerRows(isActive: Boolean): List<Pair<SimpleDateTableRow, OfferUsage?>> {
        // an offer of the plugin is active by its end date and its coupons, an offer, which ended, is inactive, also
        // if it was used recently
        val offerRows = offers.filter { it.isActive(today) == isActive }
            .sortedByDescending { it.offer.startDate }
            .map { offerRow(it, usages.usage(it.offer.id)) }
        // the other offers and discounts have no dates, they're active if they were used recently
        val recentUseStart = today.add(0, 0, -RECENT_USE_DAYS)
        val otherRows = (usages.otherOffers.entries + usages.otherDiscounts.entries)
            .filter { (_, usage) -> usage.lastUse.let { it != null && it >= recentUseStart } == isActive }
            .map { (name, usage) -> otherRow(name, usage) }
        // the latest use first, unused offers last, newest first
        return (offerRows + otherRows).sortedByDescending { it.second?.lastUse?.sortValue ?: Long.MIN_VALUE }
    }

    private fun offerRow(offerWithCoupons: OfferWithCoupons, usage: OfferUsage?): Pair<SimpleDateTableRow, OfferUsage?> {
        val offer = offerWithCoupons.offer
        val discount = when {
            offer.discountPercent != null -> formatPercent(offer.discountPercent!!.toDouble())
            offer.freePeriodInMonths != null -> "${offer.freePeriodInMonths} months free"
            else -> null
        }
        val row = SimpleDateTableRow(
            values = mapOf(
                columnName to nameWithChart(offer.name, offer.name, usage),
                columnDiscount to discount,
                // like the subscriptions of the page of a sale, an open end is left out
                columnDates to "${offer.startDate} — ${offer.endDate ?: ""}".trimEnd(),
            ) + usageValues(usage),
            tooltips = mapOf(columnName to offer.description, columnLost to lostTooltip(usage)),
            sortValues = mapOf(
                columnDiscount to (offer.discountPercent?.toLong() ?: offer.freePeriodInMonths?.toLong()),
                columnDates to offer.startDate.sortValue,
            ) + usageSortValues(usage),
        )
        return row to usage
    }

    /**
     * @param shownName The name in the table, e.g. with the percents of the discount.
     * @param discountName The name of the offer or the description of the discount in the sales.
     * @return The name with a button to show the chart of the discount amount, the name without a discount amount, see
     *  [ChartMetric.discount].
     */
    private fun nameWithChart(shownName: String, discountName: String, usage: OfferUsage?): Any {
        if (usage == null || usage.licensesWithoutLostAmount >= usage.licenses) {
            return shownName
        }
        val metric = ChartMetric.discount(discountName)
        return ChartMetricName(shownName, metric.id, metric.title, metric.unit.id(exchangeRates.targetCurrency.currencyCode))
    }

    /**
     * @return The name of a special of the Marketplace without the name of the plugin and with the rounded percents of
     *  the discount, e.g. `Student discount (100 %)` of `Student discount for My Plugin`, unless the name contains a
     *  percent, e.g. `50% discount for educational organisations`.
     */
    private fun nameWithPercents(name: String, usage: OfferUsage): String {
        val shortName = OfferDiscounts.withoutPluginName(name, pluginName)
        val percents = formatPercents(usage.percents)
        return when {
            percents == null || shortName.contains(Regex("\\d\\s*%")) -> shortName
            else -> "$shortName ($percents)"
        }
    }

    /**
     * @param name The name of the offer or the description of the discount.
     * @param shownName The name in the table, e.g. with the percents of the discount.
     */
    private fun otherRow(name: String, usage: OfferUsage, shownName: String = name): Pair<SimpleDateTableRow, OfferUsage?> {
        val row = SimpleDateTableRow(
            values = mapOf(
                columnName to nameWithChart(shownName, name, usage),
                columnDiscount to formatPercents(usage.percents),
            ) + usageValues(usage),
            tooltips = mapOf(
                columnDiscount to usage.percents.joinToString(", ", transform = ::formatPercent).ifEmpty { null },
                columnLost to lostTooltip(usage),
            ),
            sortValues = mapOf(columnDiscount to usage.percents.maxOrNull()?.toLong()) + usageSortValues(usage),
        )
        return row to usage
    }

    private fun usageValues(usage: OfferUsage?): Map<DataTableColumn, Any?> {
        if (usage == null) {
            return mapOf(columnLicenses to 0.toBigInteger())
        }
        return mapOf(
            columnLicenses to usage.licenses.toBigInteger(),
            // the numbers link to the lists of the licenses and customers with the discount
            columnSales to LinkedOfferUsage(usage.sales.toBigInteger(), OfferUsageList.Licenses, usage.key, pluginId!!),
            columnCustomers to LinkedOfferUsage(usage.customers.toBigInteger(), OfferUsageList.Customers, usage.key, pluginId!!),
            columnPaid to usage.paid.getTotalAmount(),
            columnLost to if (usage.licensesWithoutLostAmount < usage.licenses) usage.lost.getTotalAmount() else null,
            columnFirstUse to usage.firstUse,
            columnLastUse to usage.lastUse,
        )
    }

    private fun usageSortValues(usage: OfferUsage?): Map<DataTableColumn, Long?> {
        if (usage == null) {
            return mapOf(columnLicenses to 0L, columnLastUse to -1L, columnFirstUse to -1L)
        }
        return mapOf(
            columnLicenses to usage.licenses.toLong(),
            columnSales to usage.sales.toLong(),
            columnCustomers to usage.customers.toLong(),
            columnPaid to usage.paid.getTotalAmount().sortValue(),
            columnLost to usage.lost.getTotalAmount().sortValue(),
            columnFirstUse to usage.firstUse?.sortValue,
            columnLastUse to usage.lastUse?.sortValue,
        )
    }

    private fun lostTooltip(usage: OfferUsage?): String? {
        val count = usage?.licensesWithoutLostAmount ?: return null
        return when (count) {
            0 -> null
            1 -> "1 license with a discount of 100 % or an unknown percent isn't included"
            else -> "$count licenses with a discount of 100 % or an unknown percent aren't included"
        }
    }

    private fun footer(usages: List<OfferUsage>): SimpleRowGroup? {
        if (usages.isEmpty()) {
            return null
        }
        val paid = MonetaryAmountTracker(exchangeRates)
        val lost = MonetaryAmountTracker(exchangeRates)
        usages.forEach {
            paid.add(it.paid)
            lost.add(it.lost)
        }
        return SimpleRowGroup(
            SimpleDateTableRow(
                values = mapOf(
                    columnLicenses to usages.sumOf { it.licenses }.toBigInteger(),
                    columnPaid to paid.getTotalAmount(),
                    columnLost to if (usages.any { it.licensesWithoutLostAmount < it.licenses }) lost.getTotalAmount() else null,
                ),
            )
        )
    }

    /**
     * @return The rounded percents, the range of the percents if there are more than three.
     *  The percents of a discount differ a little in sales of different currencies, e.g. 24.98 % and 25 %.
     */
    private fun formatPercents(percents: Collection<Double>): String? {
        val rounded = percents.map { BigDecimal.valueOf(it).setScale(0, RoundingMode.HALF_UP).toDouble() }.distinct().sorted()
        return when {
            rounded.isEmpty() -> null
            rounded.size <= 3 -> rounded.joinToString(", ", transform = ::formatPercent)
            else -> "${formatPercent(rounded.first()).removeSuffix(" %")}–${formatPercent(rounded.last())}"
        }
    }

    private fun formatPercent(percent: Double): String {
        return BigDecimal.valueOf(percent).stripTrailingZeros().toPlainString() + " %"
    }

    internal companion object {
        /** The other offers and discounts, which were used in this number of days before today, are active. */
        const val RECENT_USE_DAYS = 90
    }

    private data class OfferWithCoupons(val offer: PluginOffer, val coupons: List<PluginOfferCoupon>?) {
        /** An offer is active, if it hasn't ended and has an active coupon, or if its coupons are unknown. */
        fun isActive(today: YearMonthDay): Boolean {
            val endDate = offer.endDate
            return (endDate == null || endDate >= today) && (coupons == null || coupons.any(PluginOfferCoupon::isActive))
        }
    }
}
