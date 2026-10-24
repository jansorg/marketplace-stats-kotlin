/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.mcp

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import dev.ja.marketplace.client.YearMonthDay
import java.math.BigDecimal

/**
 * Tests of the MCP tools for sales, licenses, prices and releases.
 */
class MarketplaceMcpRecordToolsTest {
    private val tools = MarketplaceMcpTools(McpTestData.provider)
    private val toolsWithPricing = MarketplaceMcpTools(McpTestData.providerWithPricing)

    @Test
    suspend fun listAllSales() {
        val result = tools.call("list_sales", arguments())
        assertEquals(3, result["totalCount"]!!.jsonPrimitive.int)
        assertFalse(result["truncated"]!!.jsonPrimitive.boolean)
        assertEquals("USD", result["currency"]!!.jsonPrimitive.content)
        assertEquals("2025-02-01", result["latestSaleDate"]!!.jsonPrimitive.content)

        val totals = result["totals"]!!.jsonObject
        assertEquals(3, totals["sales"]!!.jsonPrimitive.int)
        assertEquals(BigDecimal("410.00"), totals["amount"]!!.jsonPrimitive.content.toBigDecimal())
        assertEquals(3, totals["newLicenses"]!!.jsonPrimitive.int)
        assertEquals(0, BigDecimal("61.50").compareTo(totals["fee"]!!.jsonPrimitive.content.toBigDecimal()))
        assertEquals(0, BigDecimal("348.50").compareTo(totals["net"]!!.jsonPrimitive.content.toBigDecimal()))
        // the currency is USD, there's no extra amount in USD
        assertNull(totals["amountUSD"])

        val sales = result["sales"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("ref-1", "ref-2", "ref-3"), sales.map { it["refNum"]!!.jsonPrimitive.content })

        val sale = sales.first()
        assertEquals("2024-03-10", sale["date"]!!.jsonPrimitive.content)
        assertEquals("annual", sale["licensePeriod"]!!.jsonPrimitive.content)
        assertEquals(1, sale["customer"]!!.jsonObject["id"]!!.jsonPrimitive.int)
        assertNull(sale["reseller"])
        assertEquals(BigDecimal("100.00"), sale["amount"]!!.jsonPrimitive.content.toBigDecimal())
        // the fee of the Marketplace since July 2020 is 15%
        assertEquals(BigDecimal("15.00"), sale["fee"]!!.jsonPrimitive.content.toBigDecimal())
        assertNull(sale["amountUSD"])
        assertNull(sale["customerAmount"])

        val lineItem = sale["lineItems"]!!.jsonArray.single().jsonObject
        assertEquals("new", lineItem["type"]!!.jsonPrimitive.content)
        assertEquals(listOf("license-ref-1"), lineItem["licenseIds"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("2024-03-10", lineItem["validFrom"]!!.jsonPrimitive.content)
        assertEquals("2025-03-09", lineItem["validUntil"]!!.jsonPrimitive.content)
        // a single line item has the amounts of the sale, empty values are left out
        assertNull(lineItem["amount"])
        assertNull(lineItem["discounts"])
        assertNull(lineItem["isFreeLicense"])
    }

    @Test
    suspend fun listSalesWithFilters() {
        assertEquals(listOf("ref-2"), refNums(tools.call("list_sales", arguments { put("customerId", 2) })))
        // numbers as strings are accepted, too
        assertEquals(listOf("ref-2"), refNums(tools.call("list_sales", arguments { put("customerId", "2") })))
        assertEquals(listOf("ref-3"), refNums(tools.call("list_sales", arguments { put("licenseId", "license-ref-3") })))
        assertEquals(listOf("ref-3"), refNums(tools.call("list_sales", arguments { put("refNum", "ref-3") })))
        assertEquals(listOf("ref-2", "ref-3"), refNums(tools.call("list_sales", arguments { put("country", "us") })))
        assertEquals(listOf("ref-2"), refNums(tools.call("list_sales", arguments { put("customerType", "organization") })))
        assertEquals(listOf("ref-3"), refNums(tools.call("list_sales", arguments { put("licensePeriod", "Monthly") })))
        assertEquals(emptyList<String>(), refNums(tools.call("list_sales", arguments { put("saleType", "Renew") })))
        assertEquals(
            listOf("ref-1", "ref-2"),
            refNums(tools.call("list_sales", arguments { put("startDate", "2024-01-01"); put("endDate", "2024-12-31") }))
        )
        assertEquals(emptyList<String>(), refNums(tools.call("list_sales", arguments { put("customerId", 1); put("licenseId", "license-ref-3") })))

        assertThrows<IllegalArgumentException> { tools.call("list_sales", arguments { put("customerId", "abc") }) }
        assertThrows<IllegalArgumentException> { tools.call("list_sales", arguments { put("licensePeriod", "weekly") }) }
    }

    @Test
    suspend fun listSalesPages() {
        val first = tools.call("list_sales", arguments { put("limit", 2) })
        assertEquals(listOf("ref-1", "ref-2"), refNums(first))
        assertEquals(3, first["totalCount"]!!.jsonPrimitive.int)
        assertTrue(first["truncated"]!!.jsonPrimitive.boolean)
        // the totals include all sales
        assertEquals(3, first["totals"]!!.jsonObject["sales"]!!.jsonPrimitive.int)

        val second = tools.call("list_sales", arguments { put("limit", 2); put("offset", 2) })
        assertEquals(listOf("ref-3"), refNums(second))
        assertFalse(second["truncated"]!!.jsonPrimitive.boolean)

        assertThrows<IllegalArgumentException> { tools.call("list_sales", arguments { put("limit", 0) }) }
    }

    @Test
    suspend fun listSalesGroups() {
        val result = tools.call("list_sales", arguments { put("groupBy", "customerType") })
        assertNull(result["sales"])
        val groups = result["groups"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("individual", "organization"), groups.map { it["customerType"]!!.jsonPrimitive.content })
        assertEquals(listOf(2, 1), groups.map { it["sales"]!!.jsonPrimitive.int })
        assertEquals(BigDecimal("110.00"), groups[0]["amount"]!!.jsonPrimitive.content.toBigDecimal())

        val months = tools.call("list_sales", arguments { put("groupBy", "month") })["groups"]!!.jsonArray
        assertEquals(listOf("2024-03", "2024-11", "2025-02"), months.map { it.jsonObject["month"]!!.jsonPrimitive.content })
    }

    @Test
    suspend fun listCustomers() {
        val result = tools.call("list_customers", arguments())
        val customers = result["customers"]!!.jsonArray.map { it.jsonObject }
        // sorted by sales, the customer with a trial only has no licenses
        assertEquals(listOf(2, 1, 3), customers.map { it["id"]!!.jsonPrimitive.int })
        assertEquals(0, result["activeCustomers"]!!.jsonPrimitive.int)

        val organization = customers[0]
        assertEquals("organization", organization["type"]!!.jsonPrimitive.content)
        assertEquals("churned", organization["status"]!!.jsonPrimitive.content)
        assertEquals("2024-11-05", organization["since"]!!.jsonPrimitive.content)
        assertEquals("2025-11-05", organization["churnDate"]!!.jsonPrimitive.content)
        assertEquals(BigDecimal("300.00"), organization["totalSales"]!!.jsonPrimitive.content.toBigDecimal())

        val churned = tools.call("list_customers", arguments { put("churnedFrom", "2025-03-01"); put("churnedTo", "2025-03-31") })
        assertEquals(listOf(3, 1), churned["customers"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.int }.sortedDescending())

        val bySince = tools.call("list_customers", arguments { put("sortBy", "since"); put("country", "US") })
        assertEquals(listOf(2, 3), bySince["customers"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.int })

        val active = tools.call("list_customers", arguments { put("status", "active") })
        assertEquals(0, active["totalCount"]!!.jsonPrimitive.int)
    }

    @Test
    suspend fun listLicenses() {
        val result = tools.call("list_licenses", arguments())
        val licenses = result["licenses"]!!.jsonArray.map { it.jsonObject }
        // sorted by the end of validity
        assertEquals(listOf("license-ref-3", "license-ref-1", "license-ref-2"), licenses.map { it["licenseId"]!!.jsonPrimitive.content })
        assertEquals("inactive", licenses[0]["status"]!!.jsonPrimitive.content)
        assertEquals(3, licenses[0]["customer"]!!.jsonObject["id"]!!.jsonPrimitive.int)
        assertEquals(0, licenses[0]["renewals"]!!.jsonPrimitive.int)

        val expiring = tools.call("list_licenses", arguments { put("expiresFrom", "2025-03-01"); put("expiresTo", "2025-03-31") })
        assertEquals(listOf("license-ref-1"), expiring["licenses"]!!.jsonArray.map { it.jsonObject["licenseId"]!!.jsonPrimitive.content })

        val monthly = tools.call("list_licenses", arguments { put("licensePeriod", "monthly") })
        assertEquals(1, monthly["totalCount"]!!.jsonPrimitive.int)
    }

    @Test
    suspend fun customerLicenses() {
        val result = tools.call("list_customer_licenses", arguments { put("customerId", 1) })
        assertEquals("Individual DE", result["customer"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals(1, result["licenseCount"]!!.jsonPrimitive.int)
        assertEquals(0, result["activeLicenseCount"]!!.jsonPrimitive.int)

        val license = result["licenses"]!!.jsonArray.single().jsonObject
        assertEquals("license-ref-1", license["licenseId"]!!.jsonPrimitive.content)
        assertEquals("inactive", license["status"]!!.jsonPrimitive.content)
        assertEquals("annual", license["licensePeriod"]!!.jsonPrimitive.content)
        assertEquals("2024-03-10", license["validFrom"]!!.jsonPrimitive.content)
        assertEquals("2025-03-09", license["validUntil"]!!.jsonPrimitive.content)
        assertEquals(BigDecimal("100.00"), license["totalAmount"]!!.jsonPrimitive.content.toBigDecimal())

        val purchase = license["purchases"]!!.jsonArray.single().jsonObject
        assertEquals("ref-1", purchase["refNum"]!!.jsonPrimitive.content)
        assertEquals(BigDecimal("15.00"), purchase["fee"]!!.jsonPrimitive.content.toBigDecimal())
        // paid purchases have no flag
        assertNull(purchase["free"])
    }

    @Test
    suspend fun customerLicensesOfCustomerWithTrialOnly() {
        val result = tools.call("list_customer_licenses", arguments { put("customerId", 4) })
        assertEquals(0, result["licenseCount"]!!.jsonPrimitive.int)
    }

    @Test
    suspend fun customerLicensesInvalidArguments() {
        assertThrows<IllegalArgumentException> { tools.call("list_customer_licenses", arguments()) }
        assertThrows<IllegalArgumentException> { tools.call("list_customer_licenses", arguments { put("customerId", 999) }) }
    }

    @Test
    suspend fun pricingOfAllCountries() {
        val result = toolsWithPricing.call("get_pricing", arguments { put("includeTax", true) })
        val groups = result["priceGroups"]!!.jsonArray.map { it.jsonObject }
        // Germany and France have the same prices
        assertEquals(listOf(listOf("DE", "FR"), listOf("US")), groups.map { group -> group["countries"]!!.jsonArray.map { it.jsonPrimitive.content } }.sortedBy { it.first() })

        val germany = groups.first { it["currency"]!!.jsonPrimitive.content == "EUR" }
        val individual = germany["individual"]!!.jsonObject
        assertEquals(listOf("50.00", "40.00", "30.00"), individual["annual"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("59.50", "47.60", "35.70"), individual["annualTaxed"]!!.jsonArray.map { it.jsonPrimitive.content })
        // periods, which aren't offered, are missing
        assertNull(individual["monthly"])
        assertNull(individual["perpetual"])

        val us = groups.first { it["currency"]!!.jsonPrimitive.content == "USD" }["individual"]!!.jsonObject
        assertEquals(listOf("50.00"), us["annual"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(JsonNull, us["annualTaxed"]!!.jsonArray.single())
        assertEquals(BigDecimal("150.00"), us["perpetual"]!!.jsonPrimitive.content.toBigDecimal())

        // without taxes
        val withoutTax = toolsWithPricing.call("get_pricing", arguments())["priceGroups"]!!.jsonArray.first().jsonObject
        assertNull(withoutTax["individual"]!!.jsonObject["annualTaxed"])
    }

    @Test
    suspend fun pricingOfSelectedCountries() {
        val result = toolsWithPricing.call("get_pricing", arguments { put("countryCodes", buildJsonArray { add("de") }) })
        val group = result["priceGroups"]!!.jsonArray.single().jsonObject
        assertEquals(listOf("DE"), group["countries"]!!.jsonArray.map { it.jsonPrimitive.content })

        assertThrows<IllegalArgumentException> {
            toolsWithPricing.call("get_pricing", arguments { put("countryCodes", buildJsonArray { add("XX") }) })
        }
        // the plugin data without prices
        assertThrows<IllegalArgumentException> { tools.call("get_pricing", arguments()) }
    }

    @Test
    suspend fun priceChanges() {
        // the test data has too few undiscounted sales in the United States
        val result = tools.call("get_price_changes", arguments())
        assertEquals(0, result["priceChanges"]!!.jsonArray.size)

        val sales = listOf(
            McpTestData.basePriceSale(101, YearMonthDay(2024, 1, 10), 200),
            McpTestData.basePriceSale(102, YearMonthDay(2024, 2, 10), 200),
            McpTestData.basePriceSale(103, YearMonthDay(2024, 6, 10), 250),
            McpTestData.basePriceSale(104, YearMonthDay(2024, 7, 10), 250),
        )
        val changes = MarketplaceMcpTools(McpTestData.providerWithSales(sales)).call("get_price_changes", arguments())["priceChanges"]!!.jsonArray
        val change = changes.single().jsonObject
        assertEquals("2024-06-10", change["date"]!!.jsonPrimitive.content)
        assertEquals(BigDecimal("250.00"), change["prices"]!!.jsonObject["organization"]!!.jsonPrimitive.content.toBigDecimal())
    }

    @Test
    suspend fun countryArguments() {
        // ISO codes are mapped to the names of the countries, which the customers have
        assertEquals(listOf("ref-1"), refNums(tools.call("list_sales", arguments { put("country", "de") })))
        assertEquals(listOf("ref-1"), refNums(tools.call("list_sales", arguments { put("country", "Germany") })))
        val trials = MarketplaceMcpTools(McpTestData.provider).call("list_trials", arguments { put("country", "FR") })
        assertEquals(1, trials["summary"]!!.jsonObject["trials"]!!.jsonPrimitive.int)
    }

    @Test
    suspend fun pluginReleases() {
        val all = tools.call("list_plugin_releases", arguments())
        assertEquals("stable", all["channel"]!!.jsonPrimitive.content)
        assertEquals(3, all["releaseCount"]!!.jsonPrimitive.int)
        val release = all["releases"]!!.jsonArray.first().jsonObject
        assertEquals("2.1.0", release["version"]!!.jsonPrimitive.content)
        assertEquals("2025-06-01", release["date"]!!.jsonPrimitive.content)
        assertEquals(30, release["downloads"]!!.jsonPrimitive.int)
        assertEquals(listOf("1.0", "2.0"), versions(all["majorReleases"]!!))

        val filtered = tools.call("list_plugin_releases", arguments { put("startDate", "2025-01-01") })
        assertEquals(listOf("2.1.0", "2.0.0"), versions(filtered["releases"]!!))
        assertEquals(listOf("2.0"), versions(filtered["majorReleases"]!!))

        val eap = tools.call("list_plugin_releases", arguments { put("channel", "eap") })
        assertEquals(0, eap["releaseCount"]!!.jsonPrimitive.int)
    }

    @Test
    suspend fun jetBrainsMajorReleases() {
        val result = tools.call("list_jetbrains_product_releases", buildJsonObject { put("majorOnly", true) })
        assertEquals("IIU", result["productCode"]!!.jsonPrimitive.content)
        assertNull(result["releases"])
        val releases = result["majorReleases"]!!.jsonArray.map { it.jsonObject }
        // newest first, like the releases
        assertEquals(listOf("2025.1", "2024.3"), releases.map { it["version"]!!.jsonPrimitive.content })
        assertEquals("2025-04-16", releases[0]["date"]!!.jsonPrimitive.content)

        val filtered = tools.call("list_jetbrains_product_releases", buildJsonObject { put("majorOnly", true); put("startDate", "2025-01-01") })
        assertEquals(listOf("2025.1"), versions(filtered["majorReleases"]!!))

        // unknown product codes are errors, known products without releases aren't
        assertThrows<IllegalArgumentException> {
            tools.call("list_jetbrains_product_releases", buildJsonObject { put("majorOnly", true); put("productCode", "unknown") })
        }
        val teamCity = tools.call("list_jetbrains_product_releases", buildJsonObject { put("majorOnly", true); put("productCode", "TC") })
        assertEquals(0, teamCity["majorReleases"]!!.jsonArray.size)
    }

    @Test
    suspend fun jetBrainsProductReleases() {
        val result = tools.call("list_jetbrains_product_releases", JsonObject(emptyMap()))
        assertEquals(listOf("2025.1.1", "2025.1", "2024.3"), versions(result["releases"]!!))
        assertEquals("release", result["type"]!!.jsonPrimitive.content)

        val filtered = tools.call("list_jetbrains_product_releases", buildJsonObject { put("endDate", "2025-04-30") })
        assertEquals(listOf("2025.1", "2024.3"), versions(filtered["releases"]!!))

        val eap = tools.call("list_jetbrains_product_releases", buildJsonObject { put("type", "eap") })
        assertEquals(0, eap["releaseCount"]!!.jsonPrimitive.int)

        assertThrows<IllegalArgumentException> {
            tools.call("list_jetbrains_product_releases", buildJsonObject { put("type", "nightly") })
        }
    }

    @Test
    suspend fun jetBrainsProductRelease() {
        val latest = tools.call("get_jetbrains_product_release", JsonObject(emptyMap()))["release"]!!.jsonObject
        assertEquals("2025.1.1", latest["version"]!!.jsonPrimitive.content)
        assertFalse(latest["isSecurityCritical"]!!.jsonPrimitive.boolean)
        // downloads and what's new are optional
        assertNull(latest["downloads"])
        assertNull(latest["whatsNew"])

        val details = tools.call("get_jetbrains_product_release", buildJsonObject { put("includeDownloads", true) })["release"]!!.jsonObject
        assertEquals(0, details["downloads"]!!.jsonArray.size)

        val version = tools.call("get_jetbrains_product_release", buildJsonObject { put("version", "2024.3") })["release"]!!.jsonObject
        assertEquals("2024-11-14", version["date"]!!.jsonPrimitive.content)

        assertThrows<IllegalArgumentException> {
            tools.call("get_jetbrains_product_release", buildJsonObject { put("version", "1999.1") })
        }
    }

    @Test
    fun htmlToText() {
        assertEquals("Title\n- One & two\n- Three", McpReleasesJson.htmlToText("<h2>Title</h2><ul><li>One &amp; two</li>\n<li>Three</li></ul>"))
    }

    @Test
    suspend fun jetBrainsProducts() {
        val all = tools.call("list_jetbrains_products", JsonObject(emptyMap()))
        val products = all["products"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("IIU", "TC"), products.map { it["code"]!!.jsonPrimitive.content })

        val ide = products[0]
        assertTrue(ide["forSale"]!!.jsonPrimitive.boolean)
        assertEquals(listOf("IDE"), ide["categories"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("2025.1.1", ide["latestRelease"]!!.jsonObject["version"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, products[1]["latestRelease"])

        val filtered = tools.call("list_jetbrains_products", buildJsonObject { put("category", "ide") })
        assertEquals(1, filtered["productCount"]!!.jsonPrimitive.int)
    }

    @Test
    suspend fun salesInOtherCurrency() {
        val result = tools.call("list_sales", arguments { put("currency", "eur") })
        assertEquals("EUR", result["currency"]!!.jsonPrimitive.content)

        val sale = result["sales"]!!.jsonArray.first().jsonObject
        assertEquals(BigDecimal("50.00"), sale["amount"]!!.jsonPrimitive.content.toBigDecimal())
        assertEquals(BigDecimal("100.00"), sale["amountUSD"]!!.jsonPrimitive.content.toBigDecimal())
        // the customer paid in USD
        assertEquals(BigDecimal("100.00"), sale["customerAmount"]!!.jsonPrimitive.content.toBigDecimal())
        assertEquals("USD", sale["customerCurrency"]!!.jsonPrimitive.content)
        assertEquals(BigDecimal("205.00"), result["totals"]!!.jsonObject["amount"]!!.jsonPrimitive.content.toBigDecimal())

        val jpy = tools.call("list_sales", arguments { put("currency", "JPY") })
        assertEquals(BigDecimal("8000.00"), jpy["sales"]!!.jsonArray.first().jsonObject["amount"]!!.jsonPrimitive.content.toBigDecimal())

        val licenses = tools.call("list_customer_licenses", arguments { put("customerId", 1); put("currency", "EUR") })
        assertEquals(BigDecimal("50.00"), licenses["licenses"]!!.jsonArray.single().jsonObject["totalAmount"]!!.jsonPrimitive.content.toBigDecimal())
    }

    @Test
    suspend fun tableInOtherCurrency() {
        val result = tools.call("get_sales_and_trials_breakdown", arguments { put("currency", "EUR") })
        assertEquals("EUR", result["currency"]!!.jsonPrimitive.content)
        val footer = result["sections"]!!.jsonArray.first().jsonObject["footer"]!!.jsonArray.first().jsonArray
        assertTrue(footer.any { (it as? JsonPrimitive)?.content == "205.00" }, footer.toString())
        // the sections of the breakdown have different columns
        val columns = result["sections"]!!.jsonArray.first().jsonObject["columns"]!!.jsonArray
        assertTrue(columns.any { it.jsonObject["unit"]?.jsonPrimitive?.content == "EUR" }, columns.toString())
    }

    @Test
    suspend fun unsupportedCurrency() {
        assertThrows<IllegalArgumentException> { tools.call("list_sales", arguments { put("currency", "XYZ") }) }
        // no recent rates
        assertThrows<IllegalArgumentException> { tools.call("get_monthly_overview", arguments { put("currency", "CYP") }) }
    }

    @Test
    suspend fun latestExchangeRates() {
        val result = tools.call("get_exchange_rates", JsonObject(emptyMap()))
        assertEquals(JsonNull, result["date"])
        assertEquals("2025-01-31", result["rateDate"]!!.jsonPrimitive.content)
        assertEquals("EUR", result["baseCurrency"]!!.jsonPrimitive.content)
        val rates = result["rates"]!!.jsonObject
        assertEquals(setOf("USD", "JPY"), rates.keys)
        assertEquals(0, BigDecimal("170").compareTo(rates["JPY"]!!.jsonPrimitive.content.toBigDecimal()))
        assertEquals(listOf("EUR", "JPY", "USD"), result["availableCurrencies"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    suspend fun exchangeRatesOfDate() {
        val result = tools.call("get_exchange_rates", buildJsonObject {
            put("date", "2024-03-10")
            put("baseCurrency", "usd")
            put("currencies", buildJsonArray { add("EUR"); add("JPY"); add("CYP") })
        })
        assertEquals("2024-03-10", result["date"]!!.jsonPrimitive.content)
        assertEquals("2024-03-08", result["rateDate"]!!.jsonPrimitive.content)
        val rates = result["rates"]!!.jsonObject
        assertEquals(0, BigDecimal("0.5").compareTo(rates["EUR"]!!.jsonPrimitive.content.toBigDecimal()))
        assertEquals(0, BigDecimal("80").compareTo(rates["JPY"]!!.jsonPrimitive.content.toBigDecimal()))
        assertEquals(0, BigDecimal("0.25").compareTo(rates["CYP"]!!.jsonPrimitive.content.toBigDecimal()))

        assertThrows<IllegalArgumentException> {
            tools.call("get_exchange_rates", buildJsonObject { put("currencies", buildJsonArray { add("CYP") }) })
        }
        assertThrows<IllegalArgumentException> { tools.call("get_exchange_rates", buildJsonObject { put("date", "2024-02-30") }) }
        // dates far before the first rates and future dates have no rates
        assertThrows<IllegalArgumentException> { tools.call("get_exchange_rates", buildJsonObject { put("date", "2020-01-01") }) }
        assertThrows<IllegalArgumentException> { tools.call("get_exchange_rates", buildJsonObject { put("date", "2999-01-01") }) }
    }

    @Test
    suspend fun refreshData() {
        val before = McpTestData.droppedCaches
        val result = tools.call("refresh_data", JsonObject(emptyMap()))
        assertTrue(result["refreshed"]!!.jsonPrimitive.boolean)
        assertEquals(before + 1, McpTestData.droppedCaches)

        val plugin = tools.call("refresh_data", arguments())
        assertEquals(McpTestData.PLUGIN_ID, plugin["pluginId"]!!.jsonPrimitive.int)
        assertEquals(before + 2, McpTestData.droppedCaches)
        assertFalse(tools.tools.single { it.name == "refresh_data" }.readOnly)
    }

    @Test
    suspend fun offersWithTheNumberOfCouponsWithoutTheirCodes() {
        val result = tools.call("list_offers", arguments())
        val offers = result["offers"]!!.jsonArray.map { it.jsonObject }
        // newest first
        assertEquals(listOf("Free months", "Launch offer"), offers.map { it["name"]!!.jsonPrimitive.content })

        val launch = offers[1]
        assertEquals(50, launch["discountPercent"]!!.jsonPrimitive.int)
        assertEquals(JsonNull, launch["freeMonths"])
        assertEquals(listOf("annual"), launch["licensePeriods"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(3, launch["coupons"]!!.jsonPrimitive.int)
        assertEquals(2, launch["activeCoupons"]!!.jsonPrimitive.int)
        assertEquals(2, launch["sentCoupons"]!!.jsonPrimitive.int)
        assertEquals(3, offers[0]["freeMonths"]!!.jsonPrimitive.int)
        assertEquals(0, offers[0]["coupons"]!!.jsonPrimitive.int)

        assertFalse(result.toString().contains("CODE-"), "the coupon codes must not be returned")
    }

    @Test
    suspend fun lineItemsOfSalesWithoutAmountAreFreeLicenses() {
        val sales = listOf(McpTestData.changedSale("R-ZERO", YearMonthDay(2024, 5, 10)))
        val result = MarketplaceMcpTools(McpTestData.providerWithSales(sales)).call("list_sales", arguments { put("refNum", "R-ZERO") })
        val items = result["sales"]!!.jsonArray.single().jsonObject["lineItems"]!!.jsonArray.map { it.jsonObject }
        assertEquals(2, items.size)
        for (item in items) {
            // the amounts of the line items are limited to the total of the sale
            assertEquals(BigDecimal("0.00"), item["amount"]!!.jsonPrimitive.content.toBigDecimal())
            assertTrue(item["isFreeLicense"]!!.jsonPrimitive.boolean)
        }
    }

    @Test
    suspend fun monetizationOfPaidPlugins() {
        val plugin = tools.call("list_plugins", JsonObject(emptyMap()))["plugins"]!!.jsonArray[0].jsonObject
        val monetization = plugin["monetization"]!!.jsonObject
        assertEquals("PTEST", monetization["productCode"]!!.jsonPrimitive.content)
        assertEquals("PAID", monetization["pricingModel"]!!.jsonPrimitive.content)
        assertEquals(listOf("OBFUSCATE"), monetization["confirmedRequirements"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    private fun arguments(builder: JsonObjectBuilder.() -> Unit = {}): JsonObject {
        return buildJsonObject {
            put("pluginId", McpTestData.PLUGIN_ID)
            builder()
        }
    }

    private fun refNums(result: JsonObject): List<String> {
        return result["sales"]!!.jsonArray.map { it.jsonObject["refNum"]!!.jsonPrimitive.content }
    }

    private fun versions(releases: JsonElement): List<String> {
        return releases.jsonArray.map { it.jsonObject["version"]!!.jsonPrimitive.content }
    }
}
