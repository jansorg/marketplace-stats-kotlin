/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.mcp

import dev.ja.marketplace.client.LicenseId
import dev.ja.marketplace.client.LicenseInfo
import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.client.model.CustomerInfo
import dev.ja.marketplace.client.model.LicensePeriod
import dev.ja.marketplace.client.model.PluginSale
import dev.ja.marketplace.client.model.PluginSaleItemType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put
import java.math.BigDecimal

/**
 * The page of a list, which is returned by a tool, read from the arguments `limit` and `offset`.
 */
internal class Page(val limit: Int, val offset: Int) {
    fun <T> apply(items: List<T>): List<T> = items.drop(offset).take(limit)

    /**
     * Adds the total number of items and if items after this page are left out.
     */
    fun putInfo(builder: JsonObjectBuilder, totalCount: Int) {
        builder.put("totalCount", totalCount)
        if (offset > 0) {
            builder.put("offset", offset)
        }
        builder.put("truncated", offset + limit < totalCount)
    }

    companion object {
        const val DEFAULT_LIMIT = 100
        const val MAX_LIMIT = 1000

        fun of(arguments: JsonObject): Page {
            val limit = arguments.intArgument("limit") ?: DEFAULT_LIMIT
            val offset = arguments.intArgument("offset") ?: 0
            require(limit in 1..MAX_LIMIT) { "limit must be between 1 and $MAX_LIMIT" }
            require(offset >= 0) { "offset must not be negative" }
            return Page(limit, offset)
        }
    }
}

internal enum class SaleGrouping(val id: String, val key: (PluginSale) -> String) {
    Month("month", { "%04d-%02d".format(it.date.year, it.date.month) }),
    Year("year", { it.date.year.toString() }),
    Country("country", { it.customer.country }),
    CustomerType("customerType", { it.customer.type.toolName }),
    LicensePeriod("licensePeriod", { it.licensePeriod.toolName }),
    Customer("customerId", { it.customer.code.toString() }),
}

internal enum class CustomerSorting(val id: String, val comparator: Comparator<CustomerSummary>) {
    Sales("sales", compareByDescending { it.totalSales }),
    Since("since", compareBy(nullsLast()) { it.firstLicenseStart }),
    Until("until", compareByDescending(nullsLast()) { it.lastLicenseEnd }),
}

/**
 * A customer and the summary of the customer's licenses.
 *
 * @param churnDate The day after the end of the last license, `null` for active customers.
 */
internal class CustomerSummary(
    val customer: CustomerInfo,
    val firstLicenseStart: YearMonthDay?,
    val lastLicenseEnd: YearMonthDay?,
    val isActive: Boolean,
    val churnDate: YearMonthDay?,
    val activeLicenses: Int,
    val totalLicenses: Int,
    /** Sales in the displayed currency. */
    val totalSales: BigDecimal,
)

/**
 * A license and its purchases by the same customer or after a transfer to another customer.
 *
 * @param purchases The purchases sorted by date.
 */
internal class LicenseSummary(
    val licenseId: LicenseId,
    val purchases: List<LicenseInfo>,
    val isActive: Boolean,
) {
    /** The customer of the latest purchase. */
    val customer: CustomerInfo = purchases.last().sale.customer
    val licensePeriod: LicensePeriod = purchases.last().sale.licensePeriod
    val validFrom: YearMonthDay? = purchases.mapNotNull { it.validity?.start }.minOrNull()
    val validUntil: YearMonthDay? = purchases.mapNotNull { it.validity?.end }.maxOrNull()
    val renewals: Int = purchases.count { it.saleLineItem.type == PluginSaleItemType.Renew }

    companion object {
        fun of(licenseId: LicenseId, sortedPurchases: List<LicenseInfo>, today: YearMonthDay): LicenseSummary {
            // perpetual licenses have no end of validity
            val isActive = sortedPurchases.any { it.validity == null || it.validity!!.end >= today }
            return LicenseSummary(licenseId, sortedPurchases, isActive)
        }
    }
}
