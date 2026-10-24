/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client

import dev.ja.marketplace.client.currency.MarketplaceCurrencies
import dev.ja.marketplace.client.model.CustomerType
import dev.ja.marketplace.client.model.FullSalesReportRow
import dev.ja.marketplace.client.model.LicensePeriod
import dev.ja.marketplace.client.model.SalesAnalyticsReportRow
import dev.ja.marketplace.client.model.SalesReportLicenseType
import org.javamoney.moneta.FastMoney
import java.math.BigDecimal

/**
 * Parses the CSV files of the sales reports of the Marketplace, see [MarketplaceClient.salesAnalyticsReport] and
 * [MarketplaceClient.fullSalesReport].
 */
internal object SalesReportCsv {
    fun analyticsReport(csv: String): List<SalesAnalyticsReportRow> {
        return records(csv).map { row ->
            SalesAnalyticsReportRow(
                productCode = row.getValue("code"),
                date = YearMonthDay.parse(row.getValue("processed_date")),
                customerType = customerType(row.getValue("customer_type")),
                licenseType = SalesReportLicenseType.of(row.getValue("license_type")),
                billingPeriod = billingPeriod(row.getValue("billing_period")),
                quantity = row.getValue("sum_quantity").toInt(),
                amountUSD = FastMoney.of(BigDecimal(row.getValue("revenue_usd")), MarketplaceCurrencies.USD),
            )
        }
    }

    fun fullReport(csv: String): List<FullSalesReportRow> {
        return records(csv).map { row ->
            FullSalesReportRow(
                productCode = row.getValue("code"),
                date = YearMonthDay.parse(row.getValue("processed_date")),
                customerType = customerType(row.getValue("customer_type")),
                customerName = row.getValue("customer_name"),
                customerCountryIsoCode = row.getValue("customer_country_iso"),
                isViaReseller = row.getValue("via_reseller").toBoolean(),
                licenseType = SalesReportLicenseType.of(row.getValue("license_type")),
                billingPeriod = billingPeriod(row.getValue("billing_period")),
                quantity = row.getValue("quantity").toInt(),
                amountUSD = FastMoney.of(BigDecimal(row.getValue("amount_in_usd")), MarketplaceCurrencies.USD),
                isDiscounted = row.getValue("is_discounted").toBoolean(),
                customerId = row.getValue("customer_code").toInt(),
                orderRef = row.getValue("order_ref"),
                purchaseId = row.getValue("purchase_id"),
            )
        }
    }

    private fun customerType(value: String): CustomerType? = when (value) {
        "Organization" -> CustomerType.Organization
        "Personal" -> CustomerType.Individual
        else -> null
    }

    private fun billingPeriod(value: String): LicensePeriod? = when (value) {
        "Annual" -> LicensePeriod.Annual
        "Monthly" -> LicensePeriod.Monthly
        else -> null
    }

    /**
     * @return The records of the CSV file as maps of the column names of the header to the values.
     */
    internal fun records(csv: String): List<Map<String, String>> {
        val lines = parse(csv)
        val header = lines.firstOrNull() ?: return emptyList()
        return lines.drop(1)
            .filter { it.size > 1 || it.singleOrNull()?.isNotEmpty() == true }
            .map { values -> header.zip(values).toMap() }
    }

    /**
     * Parses CSV of RFC 4180: values are separated by commas, quoted values may contain commas, line breaks and
     * quotes, which are escaped as two quotes.
     */
    internal fun parse(csv: String): List<List<String>> {
        val records = mutableListOf<List<String>>()
        var record = mutableListOf<String>()
        val value = StringBuilder()
        var isQuoted = false
        var index = 0
        while (index < csv.length) {
            val char = csv[index]
            when {
                isQuoted && char == '"' && csv.getOrNull(index + 1) == '"' -> {
                    value.append('"')
                    index++
                }

                char == '"' -> isQuoted = !isQuoted
                isQuoted -> value.append(char)
                char == ',' -> {
                    record += value.toString()
                    value.clear()
                }

                char == '\r' && csv.getOrNull(index + 1) == '\n' -> {}
                char == '\n' || char == '\r' -> {
                    record += value.toString()
                    value.clear()
                    records += record
                    record = mutableListOf()
                }

                else -> value.append(char)
            }
            index++
        }
        if (value.isNotEmpty() || record.isNotEmpty()) {
            record += value.toString()
            records += record
        }
        return records
    }
}
