/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.mcp

import dev.ja.marketplace.client.LicenseId
import dev.ja.marketplace.client.LicenseInfo
import dev.ja.marketplace.client.Marketplace
import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.client.currency.MarketplaceCurrencies
import dev.ja.marketplace.client.model.*
import dev.ja.marketplace.exchangeRate.ExchangeRates
import kotlinx.serialization.json.*
import java.math.BigDecimal
import java.math.RoundingMode
import javax.money.MonetaryAmount

/**
 * Converts sales, licenses, customers and prices into JSON for the MCP tools.
 *
 * Amounts are plain numbers: `amount` is in the currency of the result (the displayed currency or the requested currency),
 * `amountUSD` is only added if that's not USD, `customerAmount` and `customerCurrency` only if the customer paid in another currency.
 * `fee` is the fee of the Marketplace in the currency of the result, the paid amount is `amount - fee`.
 * Default values, e.g. `false` flags, empty lists and missing dates, are left out to keep the results small.
 */
class McpRecordsJson(private val exchangeRates: ExchangeRates) {
    private val currency = exchangeRates.targetCurrency
    private val isUSD = currency == MarketplaceCurrencies.USD

    fun sale(sale: PluginSale): JsonObject {
        return buildJsonObject {
            put("refNum", sale.ref)
            put("date", sale.date.asIsoString)
            put("licensePeriod", sale.licensePeriod.toolName)
            put("customer", customer(sale.customer))
            sale.reseller?.let { put("reseller", reseller(it)) }
            putAmounts(sale.date, sale.amountUSD, sale.amount, withFee = true)
            put("lineItems", buildJsonArray {
                for (item in sale.lineItems) {
                    // Special sales, e.g. with manual changes by JetBrains, can have a total of 0 but line items with the
                    // regular amounts. Like the amounts of the licenses, the amounts are limited to the amounts of the sale.
                    val itemAmountUSD = item.amountUSD.coerceAtMost(sale.amountUSD)
                    val itemAmount = item.amount.coerceAtMost(sale.amount)
                    add(buildJsonObject {
                        put("type", item.type.toolName)
                        put("licenseIds", buildJsonArray { item.licenseIds.forEach { add(it) } })
                        (item as? SubscriptionPluginSaleItem)?.subscriptionDates?.let {
                            put("validFrom", it.start.asIsoString)
                            put("validUntil", it.end.asIsoString)
                        }
                        // the amounts of a single line item are the amounts of the sale
                        if (sale.lineItems.size > 1) {
                            putAmounts(sale.date, itemAmountUSD, itemAmount)
                        }
                        if (item.isFreeLicense || itemAmountUSD.isZero) {
                            put("isFreeLicense", true)
                        }
                        if (item.discountDescriptions.isNotEmpty()) {
                            put("discounts", buildJsonArray {
                                for (discount in item.discountDescriptions) {
                                    add(buildJsonObject {
                                        put("description", discount.description)
                                        discount.percent?.let { put("percent", it) }
                                    })
                                }
                            })
                        }
                    })
                }
            })
        }
    }

    /**
     * @return The number of sales, their amount, and the number of new and renewed licenses.
     */
    fun saleTotals(sales: List<PluginSale>): JsonObject {
        val items = sales.flatMap { sale -> sale.lineItems.map { sale to it } }
        val amounts = sales.map { displayedAmount(it.date, it.amountUSD, it.amount) }
        val fees = sales.zip(amounts).map { (sale, amount) -> Marketplace.feeAmount(sale.date, amount) }
        return buildJsonObject {
            put("sales", sales.size)
            put("amount", sum(amounts))
            put("fee", sum(fees))
            put("net", sum(amounts) - sum(fees))
            if (!isUSD) {
                put("amountUSD", sum(sales.map { it.amountUSD }))
            }
            put("newLicenses", items.filter { it.second.type == PluginSaleItemType.New }.sumOf { it.second.licenseIds.size })
            put("renewedLicenses", items.filter { it.second.type == PluginSaleItemType.Renew }.sumOf { it.second.licenseIds.size })
        }
    }

    /**
     * A license with its purchases, for the licenses of a customer.
     */
    internal fun license(license: LicenseSummary): JsonObject {
        return buildJsonObject {
            putLicense(license)
            put("purchases", buildJsonArray {
                for (purchase in license.purchases) {
                    add(buildJsonObject {
                        put("refNum", purchase.sale.ref)
                        put("date", purchase.sale.date.asIsoString)
                        put("type", purchase.saleLineItem.type.toolName)
                        purchase.validity?.let {
                            put("validFrom", it.start.asIsoString)
                            put("validUntil", it.end.asIsoString)
                        }
                        putAmounts(purchase.sale.date, purchase.amountUSD, purchase.amount, withFee = true)
                        if (!purchase.isPaidLicense) {
                            put("free", true)
                        }
                    })
                }
            })
        }
    }

    /**
     * A license with its customer and without the purchases, for the list of licenses.
     */
    internal fun licenseSummary(license: LicenseSummary): JsonObject {
        return buildJsonObject {
            putLicense(license)
            put("customer", customer(license.customer))
        }
    }

    private fun JsonObjectBuilder.putLicense(license: LicenseSummary) {
        put("licenseId", license.licenseId)
        put("status", if (license.isActive) "active" else "inactive")
        put("licensePeriod", license.licensePeriod.toolName)
        license.validFrom?.let { put("validFrom", it.asIsoString) }
        license.validUntil?.let { put("validUntil", it.asIsoString) }
        put("renewals", license.renewals)
        put("totalAmount", sum(license.purchases.map { displayedAmount(it.sale.date, it.amountUSD, it.amount) }))
        if (!isUSD) {
            put("totalAmountUSD", sum(license.purchases.map { it.amountUSD }))
        }
    }

    /**
     * @return The customers of the licenses with the summary of their licenses.
     */
    internal fun customerSummaries(licenses: List<LicenseInfo>, today: YearMonthDay): List<CustomerSummary> {
        // the status of a license depends on the purchases of all customers
        val licenseLatestEnd = HashMap<LicenseId, YearMonthDay>()
        for (license in licenses) {
            val end = license.validity?.end ?: continue
            licenseLatestEnd.merge(license.id, end, ::maxOf)
        }

        return licenses.groupBy { it.sale.customer.code }.values.map { purchases ->
            val customer = purchases.maxBy { it.sale.date }.sale.customer
            val activeLicenses = purchases.filter { it.validity == null || it.validity!!.end >= today }.mapTo(HashSet()) { it.id }
            val subscriptionLicenses = purchases.filter { it.validity != null }.mapTo(HashSet()) { it.id }
            CustomerSummary(
                customer = customer,
                firstLicenseStart = purchases.mapNotNull { it.validity?.start }.minOrNull(),
                lastLicenseEnd = purchases.mapNotNull { it.validity?.end }.maxOrNull(),
                isActive = activeLicenses.isNotEmpty(),
                activeLicenses = activeLicenses.size,
                churnedLicenses = subscriptionLicenses.count { licenseLatestEnd.getValue(it) < today },
                transferredLicenses = subscriptionLicenses.count { it !in activeLicenses && licenseLatestEnd.getValue(it) >= today },
                totalLicenses = purchases.mapTo(HashSet()) { it.id }.size,
                totalSales = sum(purchases.map { displayedAmount(it.sale.date, it.amountUSD, it.amount) }),
            )
        }
    }

    internal fun customerSummary(summary: CustomerSummary): JsonObject {
        return buildJsonObject {
            customer(summary.customer).forEach { (key, value) -> put(key, value) }
            put("status", if (summary.isActive) "active" else "inactive")
            summary.firstLicenseStart?.let { put("since", it.asIsoString) }
            summary.lastLicenseEnd?.let { put("until", it.asIsoString) }
            put("activeLicenses", summary.activeLicenses)
            put("churnedLicenses", summary.churnedLicenses)
            if (summary.transferredLicenses > 0) {
                put("transferredLicenses", summary.transferredLicenses)
            }
            put("licenses", summary.totalLicenses)
            put("totalSales", summary.totalSales)
        }
    }

    fun customer(customer: CustomerInfo): JsonObject {
        return buildJsonObject {
            put("id", customer.code)
            customer.name?.let { put("name", it) }
            put("country", customer.country)
            put("type", customer.type.toolName)
        }
    }

    /**
     * @return The prices of a country by customer type: `annual` and `monthly` are arrays with the prices
     *  of the 1st, 2nd and 3rd year of a subscription (continuity discount), only the 1st year without continuity
     *  discount, `perpetual` is a single price.
     *  The prices including VAT are added as `annualTaxed`, `monthlyTaxed` and `perpetualTaxed`, if [includeTax] is `true`.
     */
    fun prices(pricing: PluginPriceInfo, includeTax: Boolean): JsonObject {
        return buildJsonObject {
            put(CustomerType.Individual.toolName, pricesByPeriod(pricing.prices.personal, includeTax))
            put(CustomerType.Organization.toolName, pricesByPeriod(pricing.prices.commercial, includeTax))
        }
    }

    private fun pricesByPeriod(prices: PriceInfoByPeriod, includeTax: Boolean): JsonObject {
        fun years(data: PriceInfoTypeData, value: (PriceInfoData) -> BigDecimal?): JsonArray {
            return buildJsonArray { listOfNotNull(data.firstYear, data.secondYear, data.thirdYear).forEach { add(value(it)?.let(::price)) } }
        }

        return buildJsonObject {
            prices.annual?.let { annual ->
                put("annual", years(annual) { it.price })
                if (includeTax) put("annualTaxed", years(annual) { it.priceTaxed })
            }
            prices.monthly?.let { monthly ->
                put("monthly", years(monthly) { it.price })
                if (includeTax) put("monthlyTaxed", years(monthly) { it.priceTaxed })
            }
            prices.perpetual?.let { perpetual ->
                put("perpetual", price(perpetual.price))
                if (includeTax) put("perpetualTaxed", perpetual.priceTaxed?.let(::price))
            }
        }
    }

    private fun reseller(reseller: ResellerInfo): JsonObject {
        return buildJsonObject {
            put("id", reseller.code)
            put("name", reseller.name)
            put("country", reseller.country)
            put("type", reseller.type.toolName)
        }
    }

    private fun JsonObjectBuilder.putAmounts(date: YearMonthDay, amountUSD: MonetaryAmount, amount: MonetaryAmount, withFee: Boolean = false) {
        val displayed = displayedAmount(date, amountUSD, amount)
        put("amount", DataTableJson.amount(displayed))
        if (withFee) {
            put("fee", DataTableJson.amount(Marketplace.feeAmount(date, displayed)))
        }
        if (!isUSD) {
            put("amountUSD", DataTableJson.amount(amountUSD))
        }
        if (amount.currency != currency) {
            put("customerAmount", DataTableJson.amount(amount))
            put("customerCurrency", amount.currency.currencyCode)
        }
    }

    /**
     * The amount in the displayed currency, converted in the same way as by `MonetaryAmountTracker`.
     */
    private fun displayedAmount(date: YearMonthDay, amountUSD: MonetaryAmount, amount: MonetaryAmount): MonetaryAmount {
        return exchangeRates.targetAmount(date, amountUSD, amount)
    }

    private fun sum(amounts: List<MonetaryAmount>): BigDecimal {
        return amounts.fold(BigDecimal.ZERO) { sum, amount -> sum + amount.number.numberValue(BigDecimal::class.java) }
            .setScale(2, RoundingMode.HALF_UP)
    }

    private fun price(value: BigDecimal): BigDecimal = value.setScale(2, RoundingMode.HALF_UP)
}
