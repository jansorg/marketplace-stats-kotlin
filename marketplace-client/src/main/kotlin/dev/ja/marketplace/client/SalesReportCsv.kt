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
import org.slf4j.LoggerFactory
import java.math.BigDecimal

/**
 * Parses the CSV files of the sales reports of the Marketplace, see [MarketplaceClient.salesAnalyticsReport] and
 * [MarketplaceClient.fullSalesReport].
 */
internal object SalesReportCsv {
    /** The columns of the analytics report, which are read. Other columns would be ignored, see [unknownColumns]. */
    val analyticsReportColumns: Set<String> = setOf(
        "code", "processed_date", "customer_type", "license_type", "billing_period", "sum_quantity", "revenue_usd",
    )

    /** The columns of the full report, which are read. Other columns would be ignored, see [unknownColumns]. */
    val fullReportColumns: Set<String> = setOf(
        "code", "processed_date", "customer_type", "customer_name", "customer_country_iso", "via_reseller", "license_type",
        "billing_period", "quantity", "amount_in_usd", "is_discounted", "customer_code", "order_ref", "purchase_id",
    )

    /**
     * @return The columns of the header of the CSV file, which aren't in [knownColumns], e.g. new columns of the API.
     */
    fun unknownColumns(csv: String, knownColumns: Set<String>): List<String> {
        return parse(csv).firstOrNull().orEmpty().filter { it.isNotEmpty() && it !in knownColumns }
    }

    /** The columns of the analytics report, without which its rows can't be read. */
    private val analyticsReportRequiredColumns = setOf("code", "processed_date", "revenue_usd")

    /** The columns of the full report, without which its rows can't be read. */
    private val fullReportRequiredColumns = setOf("code", "processed_date", "amount_in_usd")

    private val logger = LoggerFactory.getLogger(SalesReportCsv::class.java)

    /**
     * @throws IllegalStateException If a column, which is needed for all rows, is missing, e.g. after a change of the format.
     */
    fun analyticsReport(csv: String): List<SalesAnalyticsReportRow> {
        return rows(csv, "analytics report", analyticsReportRequiredColumns) { row ->
            SalesAnalyticsReportRow(
                productCode = row.required("code"),
                date = row.date("processed_date"),
                customerType = customerType(row["customer_type"]),
                licenseType = row["license_type"]?.let(SalesReportLicenseType::of),
                billingPeriod = billingPeriod(row["billing_period"]),
                quantity = row.int("sum_quantity") ?: 0,
                amountUSD = row.amountUSD("revenue_usd"),
            )
        }
    }

    /**
     * @throws IllegalStateException If a column, which is needed for all rows, is missing, e.g. after a change of the format.
     */
    fun fullReport(csv: String): List<FullSalesReportRow> {
        return rows(csv, "full report", fullReportRequiredColumns) { row ->
            FullSalesReportRow(
                productCode = row.required("code"),
                date = row.date("processed_date"),
                customerType = customerType(row["customer_type"]),
                customerName = row["customer_name"].orEmpty(),
                customerCountryIsoCode = row["customer_country_iso"].orEmpty(),
                isViaReseller = row["via_reseller"].toBoolean(),
                licenseType = row["license_type"]?.let(SalesReportLicenseType::of),
                billingPeriod = billingPeriod(row["billing_period"]),
                quantity = row.int("quantity") ?: 0,
                amountUSD = row.amountUSD("amount_in_usd"),
                isDiscounted = row["is_discounted"].toBoolean(),
                customerId = row.int("customer_code"),
                orderRef = row["order_ref"].orEmpty(),
                purchaseId = row["purchase_id"].orEmpty(),
            )
        }
    }

    /**
     * @return The rows of the report. Rows with an invalid required value, e.g. an empty amount, are skipped and logged,
     *  the other rows are still returned.
     */
    private fun <T> rows(csv: String, name: String, requiredColumns: Set<String>, transform: (Map<String, String>) -> T): List<T> {
        val (header, records) = table(csv)
        val missing = requiredColumns - header.toSet()
        check(records.isEmpty() || missing.isEmpty()) { "The columns ${missing.sorted()} of the $name are missing" }

        return records.mapIndexedNotNull { index, row ->
            try {
                transform(row)
            } catch (e: IllegalArgumentException) {
                // the line of the row, after the header
                logger.warn("Skipping row ${index + 2} of the $name: ${e.message}")
                null
            }
        }
    }

    private fun Map<String, String>.required(column: String): String {
        return this[column]?.takeIf(String::isNotBlank) ?: throw IllegalArgumentException("$column is empty")
    }

    /** The date of the column, also of a value with a time, e.g. `2025-03-14 10:00:00`. */
    private fun Map<String, String>.date(column: String): YearMonthDay {
        val value = required(column)
        return try {
            YearMonthDay.parse(value.take(10))
        } catch (e: Exception) {
            throw IllegalArgumentException("$column isn't a date")
        }
    }

    private fun Map<String, String>.amountUSD(column: String): FastMoney {
        val value = required(column).toBigDecimalOrNull() ?: throw IllegalArgumentException("$column isn't a number")
        return fastMoneyOf(value, MarketplaceCurrencies.USD.currencyCode)
    }

    /** @return The integer of the column, also of a value like `1.0`, `null` if it's empty or invalid. */
    private fun Map<String, String>.int(column: String): Int? {
        return this[column]?.trim()?.toBigDecimalOrNull()?.toInt()
    }

    /** @return `null` for an empty value, [CustomerType.Unknown] for an unknown type. */
    private fun customerType(value: String?): CustomerType? = when {
        value.isNullOrBlank() -> null
        else -> CustomerType.entries.firstOrNull { it != CustomerType.Unknown && it.apiValue == value } ?: CustomerType.Unknown
    }

    /** @return `null` without a period, e.g. of a free license, [LicensePeriod.Unknown] for an unknown period. */
    private fun billingPeriod(value: String?): LicensePeriod? = when {
        value.isNullOrBlank() || value.equals("none", ignoreCase = true) -> null
        else -> LicensePeriod.entries.firstOrNull { it != LicensePeriod.Unknown && it.apiValue == value } ?: LicensePeriod.Unknown
    }

    /**
     * @return The records of the CSV file as maps of the column names of the header to the values.
     */
    internal fun records(csv: String): List<Map<String, String>> = table(csv).second

    /**
     * @return The columns of the header and the records of the CSV file as maps of the columns to the values.
     */
    private fun table(csv: String): Pair<List<String>, List<Map<String, String>>> {
        // a byte order mark at the start would be part of the name of the first column
        val lines = parse(csv.removePrefix("\uFEFF"))
        val header = lines.firstOrNull() ?: return emptyList<String>() to emptyList()
        val records = lines.drop(1)
            .filter { it.size > 1 || it.singleOrNull()?.isNotEmpty() == true }
            .map { values -> header.zip(values).toMap() }
        return header to records
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
