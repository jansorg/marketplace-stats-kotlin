/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.prices

import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.services.Country
import dev.ja.marketplace.client.model.CustomerType
import dev.ja.marketplace.client.model.LicensePeriod
import dev.ja.marketplace.client.model.PluginSale
import dev.ja.marketplace.client.model.PluginSaleItemType
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * A change of the annual base price of a paid plugin.
 *
 * @param prices The new prices in USD, of the customer types with a changed price.
 */
data class PriceChange(val date: YearMonthDay, val prices: Map<CustomerType, BigDecimal>) {
    val label: String
        get() {
            // the price for individuals first, it's the price shown on the Marketplace
            return listOf(CustomerType.Individual, CustomerType.Organization).mapNotNull { customerType ->
                val price = prices[customerType] ?: return@mapNotNull null
                val formattedPrice = price.stripTrailingZeros().toPlainString() + " USD"
                when (customerType) {
                    CustomerType.Individual -> "Price $formattedPrice"
                    CustomerType.Organization -> "Org. $formattedPrice"
                }
            }.joinToString(" · ")
        }
}

/**
 * The Marketplace API only provides the current prices. Changes of the base price are derived from the sales:
 * a new annual license, which is bought without a discount by a customer in the United States, pays the base price.
 */
object PriceChanges {
    /**
     * A new price must be paid by at least this number of consecutive sales,
     * single sales with a different amount are e.g. corrections of taxes.
     */
    private const val MIN_CONSECUTIVE_SALES = 2

    /**
     * The prices of all customer types usually change at the same time. With few sales, e.g. to organizations, the
     * first sale at the new price may be months later. A change is therefore combined with a change of another
     * customer type, if that change happened after the last sale at the old price and before the first sale at the
     * new price.
     */
    fun find(sales: Iterable<PluginSale>): List<PriceChange> {
        val changes = CustomerType.entries
            .flatMap { customerType -> findChanges(basePrices(sales, customerType), customerType) }
            .sortedBy { it.firstSaleWithNewPrice }

        val combined = mutableListOf<PriceChange>()
        for (change in changes) {
            val index = combined.indexOfFirst { existing ->
                change.customerType !in existing.prices
                        && existing.date > change.lastSaleWithOldPrice && existing.date <= change.firstSaleWithNewPrice
            }
            if (index >= 0) {
                combined[index] = combined[index].copy(prices = combined[index].prices + (change.customerType to change.price))
            } else {
                combined += PriceChange(change.firstSaleWithNewPrice, mapOf(change.customerType to change.price))
            }
        }
        return combined.sortedBy { it.date }
    }

    /**
     * A change of the price of one customer type. The price changed after the last sale at the old price and
     * before or at the first sale at the new price.
     */
    private data class CustomerPriceChange(
        val customerType: CustomerType,
        val price: BigDecimal,
        val lastSaleWithOldPrice: YearMonthDay,
        val firstSaleWithNewPrice: YearMonthDay,
    )

    /**
     * @return The date and the price per license of each sale, which paid the base price, sorted by date.
     */
    private fun basePrices(sales: Iterable<PluginSale>, customerType: CustomerType): List<Pair<YearMonthDay, BigDecimal>> {
        return sales
            .filter { sale ->
                sale.customer.country == Country.UNITED_STATES && sale.customer.type == customerType
                        && sale.licensePeriod == LicensePeriod.Annual && sale.reseller == null
            }
            .flatMap { sale ->
                sale.lineItems
                    .filter { it.type == PluginSaleItemType.New && it.discountDescriptions.isEmpty() && it.licenseIds.isNotEmpty() }
                    .map { item ->
                        val price = item.amountUSD.number.numberValue(BigDecimal::class.java)
                            .divide(item.licenseIds.size.toBigDecimal(), 2, RoundingMode.HALF_UP)
                        sale.date to price
                    }
            }
            .filter { (_, price) -> price.signum() > 0 }
            .sortedBy { it.first }
    }

    private fun findChanges(prices: List<Pair<YearMonthDay, BigDecimal>>, customerType: CustomerType): List<CustomerPriceChange> {
        // consecutive sales with the same price
        data class Run(val start: YearMonthDay, var end: YearMonthDay, val price: BigDecimal, var count: Int)

        val runs = mutableListOf<Run>()
        for ((date, price) in prices) {
            val last = runs.lastOrNull()
            if (last != null && last.price.compareTo(price) == 0) {
                last.count++
                last.end = date
            } else {
                runs += Run(date, date, price, 1)
            }
        }

        var current: Run? = null
        val changes = mutableListOf<CustomerPriceChange>()
        for (run in runs.filter { it.count >= MIN_CONSECUTIVE_SALES }) {
            val previous = current
            if (previous != null && previous.price.compareTo(run.price) != 0) {
                changes += CustomerPriceChange(customerType, run.price, previous.end, run.start)
            }
            current = run
        }
        return changes
    }
}
