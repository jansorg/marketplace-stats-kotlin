/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client.model

import dev.ja.marketplace.client.CustomerId
import dev.ja.marketplace.client.YearMonthDay
import javax.money.MonetaryAmount

/**
 * A row of the sales analytics report of the Marketplace, which is shown as chart of the revenue on the plugin page:
 * the sales of a day with the same customer type, license type and billing period.
 *
 * Values unknown to this client are `null`, e.g. a new license type.
 */
data class SalesAnalyticsReportRow(
    val productCode: String,
    /** The date, at which the Marketplace processed the sales. */
    val date: YearMonthDay,
    val customerType: CustomerType?,
    val licenseType: SalesReportLicenseType?,
    /** `null` for licenses without billing period. */
    val billingPeriod: LicensePeriod?,
    val quantity: Int,
    val amountUSD: MonetaryAmount,
)

/**
 * A row of the full sales report of the Marketplace: a single license of a sale, including free licenses, e.g. for
 * trials or open source projects.
 *
 * Values unknown to this client are `null`, e.g. a new license type.
 */
data class FullSalesReportRow(
    val productCode: String,
    /** The date, at which the Marketplace processed the sale. */
    val date: YearMonthDay,
    val customerType: CustomerType?,
    val customerName: String,
    val customerCountryIsoCode: String,
    val isViaReseller: Boolean,
    val licenseType: SalesReportLicenseType?,
    /** `null` for licenses without billing period. */
    val billingPeriod: LicensePeriod?,
    val quantity: Int,
    val amountUSD: MonetaryAmount,
    val isDiscounted: Boolean,
    /** `null` if the report has no customer code, e.g. for some free licenses. */
    val customerId: CustomerId?,
    val orderRef: String,
    val purchaseId: String,
)

/**
 * The license types of the sales reports.
 */
enum class SalesReportLicenseType(val reportValue: String) {
    Commercial("Commercial"),
    Personal("Personal"),
    OpenSource("OpenSource"),
    Trial("Trial");

    companion object {
        fun of(value: String): SalesReportLicenseType? = entries.firstOrNull { it.reportValue == value }
    }
}
