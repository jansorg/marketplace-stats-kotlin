/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.mcp

import dev.ja.marketplace.CustomEvent
import dev.ja.marketplace.client.LicenseInfo
import dev.ja.marketplace.client.Marketplace
import dev.ja.marketplace.client.PluginChannel
import dev.ja.marketplace.client.PluginChannelNames
import dev.ja.marketplace.client.PluginId
import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.client.YearMonthDayRange
import dev.ja.marketplace.client.model.*
import dev.ja.marketplace.data.DataTable
import dev.ja.marketplace.data.PluginData
import dev.ja.marketplace.data.chart.ChartMetricValues
import dev.ja.marketplace.data.daySummary.DaySummaryTable
import dev.ja.marketplace.data.offers.OfferSource
import dev.ja.marketplace.data.offers.OffersTable
import dev.ja.marketplace.data.offers.OffersTableKind
import dev.ja.marketplace.data.overview.OverviewTable
import dev.ja.marketplace.data.subscriptions.AverageRevenuePerCustomerTable
import dev.ja.marketplace.data.subscriptions.CohortRetentionTable
import dev.ja.marketplace.data.subscriptions.MrrMovementsTable
import dev.ja.marketplace.data.subscriptions.RealizedLifetimeValueTable
import dev.ja.marketplace.data.subscriptions.RenewalRateTable
import dev.ja.marketplace.data.subscriptions.RevenueRetentionTable
import dev.ja.marketplace.data.prices.PriceChanges
import dev.ja.marketplace.data.privingOverview.PricingOverviewTable
import dev.ja.marketplace.data.releases.IdeMajorReleases
import dev.ja.marketplace.data.releases.MajorReleases
import dev.ja.marketplace.data.timeSpanSummary.TimeSpanSummaryTable
import dev.ja.marketplace.data.topCountries.TopCountriesTable
import dev.ja.marketplace.data.topTrialCountries.TopTrialCountriesTable
import dev.ja.marketplace.data.yearSummary.YearlySummaryTable
import dev.ja.marketplace.exchangeRate.EcbRateTable
import dev.ja.marketplace.exchangeRate.ExchangeRates
import dev.ja.marketplace.services.JetBrainsProductCode
import dev.ja.marketplace.services.ProductRelease
import dev.ja.marketplace.services.ProductReleaseType
import dev.ja.marketplace.services.Products
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.datetime.toJavaLocalDate
import kotlinx.serialization.json.*
import java.math.MathContext
import java.time.LocalDate

/**
 * Data of the MCP tools, implemented by the web server.
 */
interface McpDataProvider {
    suspend fun userInfo(): UserInfo

    fun plugins(): List<PluginInfoSummary>

    suspend fun pluginData(plugin: PluginInfoSummary): PluginData

    /**
     * @return The releases of the plugin in the channel, sorted by date in descending order.
     */
    suspend fun pluginReleases(plugin: PluginInfoSummary, channel: PluginChannel): List<PluginReleaseInfo>

    /**
     * @return The release channels of the plugin, e.g. the stable channel and eap.
     */
    suspend fun pluginChannels(plugin: PluginInfoSummary): List<PluginChannel>

    /**
     * @return The special offers of a paid or freemium plugin, which customers redeem with coupons.
     */
    suspend fun offers(plugin: PluginInfoSummary): List<PluginOffer>

    /**
     * @return The coupons of a special offer of the plugin.
     */
    suspend fun offerCoupons(plugin: PluginInfoSummary, offer: PluginOfferId): List<PluginOfferCoupon>

    /**
     * @return The monetization settings of a paid or freemium plugin, e.g. its product code.
     */
    suspend fun monetization(plugin: PluginInfoSummary): PluginMonetization

    suspend fun productReleases(product: JetBrainsProductCode, type: ProductReleaseType): List<ProductRelease>

    suspend fun products(): Products

    /**
     * Drops the cached data, in memory and on disk, like the reload of the web interface.
     *
     * @param plugin If not `null`, only the data of this plugin is dropped.
     */
    fun dropCachedData(plugin: PluginInfoSummary? = null)

    /**
     * @param currencyCode ISO code of the currency, the displayed currency of the server if `null`.
     * @return Exchange rates, which convert into the currency.
     */
    fun exchangeRates(currencyCode: String?): ExchangeRates

    /**
     * @return The daily reference rates of the European Central Bank. May be slow, if the rates are downloaded.
     */
    suspend fun exchangeRateTable(): EcbRateTable

    /**
     * Downloads grouped by two dimensions, see [dev.ja.marketplace.client.MarketplaceClient.groupedDownloads].
     */
    suspend fun groupedDownloads(
        plugin: PluginInfoSummary,
        dimension: DownloadRequestDimension,
        groupType: DownloadRequestDimension,
        countType: DownloadCountType,
        startDate: YearMonthDay?,
        vararg filters: DownloadFilter,
    ): GroupedDownloadResponse

    /**
     * @return The reviews of the plugin, without the replies.
     */
    suspend fun reviews(plugin: PluginInfoSummary): List<PluginReviewComment>

    suspend fun pageAnalytics(plugin: PluginInfoSummary, timeFrame: PageAnalyticsTimeFrame): PluginPageAnalytics

    /**
     * @param plugin The plugin, or `null` for the summary of all paid plugins.
     * @return The values of the metrics of the charts, like `/chart-data`.
     * @throws IllegalArgumentException If [plugin] is `null` and the summary of all plugins is disabled.
     */
    suspend fun chartValues(plugin: PluginInfoSummary?): ChartMetricValues

    /**
     * @return The custom events of the charts, which belong to the plugins, see `--events`.
     */
    fun customEvents(plugins: Collection<PluginId>): List<CustomEvent>
}

/**
 * The result of a tool: the JSON, and content, which is returned after the JSON, e.g. an image.
 */
class McpToolResult(val json: JsonObject, val content: List<McpContent> = emptyList())

/** Content of the result of a tool. */
sealed interface McpContent {
    /** An image, e.g. a PNG, returned as base64 data. */
    class Image(val data: ByteArray, val mimeType: String) : McpContent

    class Text(val text: String) : McpContent
}

/**
 * A tool of the MCP server, independent of the MCP SDK.
 *
 * @param call Returns the result of the tool. Throws [IllegalArgumentException] for invalid arguments.
 */
class McpToolDefinition(
    val name: String,
    val title: String,
    val description: String,
    val properties: JsonObject,
    val required: List<String>,
    /** `false` if the tool changes the state of the server, e.g. drops cached data. */
    val readOnly: Boolean = true,
    /** Returns the result with content like images, `null` if the result is only the JSON of [call]. */
    val result: (suspend (arguments: JsonObject) -> McpToolResult)? = null,
    val call: suspend (arguments: JsonObject) -> JsonObject,
)

/**
 * How the optional date range of a tool is applied to a table.
 */
private enum class DateRangeFilter {
    /**
     * The table is calculated with all data, only the rows of the range are returned.
     * Used for tables with a row per day, month or year, whose values depend on earlier data,
     * e.g. the recurring revenue or the churn rate.
     */
    Rows,

    /**
     * Licenses and trials are filtered by date before they are passed to the table.
     * All sales are passed, so that the conversion of trials of the range by later sales is still found.
     */
    InputData,

    /**
     * The table is created for the date range.
     */
    Table,
}

private class TableTool(
    val name: String,
    val title: String,
    val description: String,
    val dateRangeFilter: DateRangeFilter,
    /** Arguments of the tool in addition to the plugin, the date range and the currency. */
    val properties: JsonObject = JsonObject(emptyMap()),
    /** The date range of the returned data, e.g. limited to today. The table is created with this range. */
    val effectiveDateRange: suspend (data: PluginData, dateRange: McpDateRange) -> McpDateRange = { _, dateRange -> dateRange },
    val createTable: suspend (data: PluginData, dateRange: McpDateRange, arguments: JsonObject) -> DataTable,
)

/**
 * The tools of the MCP server: account information, the plugins of the account,
 * a tool for each table of the report pages of a plugin, and tools for the sales, licenses, prices and releases.
 * The conventions of the arguments and results are described once in the instructions of the server, see [McpServerInstructions].
 */
class MarketplaceMcpTools(private val provider: McpDataProvider) {
    private val tableTools = listOf(
        TableTool(
            "get_yearly_summary",
            "Yearly summary",
            "Per year: sales, fees, paid amounts, ARR, the combined churn of all paid licenses, downloads, trials and trial conversion. " +
                    "Values are always for whole years.",
            DateRangeFilter.Rows,
        ) { _, _, _ -> YearlySummaryTable() },
        TableTool(
            "get_monthly_overview",
            "Monthly overview",
            "Per month, one section per year: sales, invoiced amounts, licenses and paid licenses at the end of the month, MRR, ARR, " +
                    "churn of annual and monthly licenses, downloads, trials and trial conversion. Values are calculated with all earlier data. " +
                    "The footer of a year has only its churn, downloads, trials and conversion, and only if all months of the year are in the range.",
            DateRangeFilter.Rows,
        ) { _, _, _ -> OverviewTable() },
        TableTool(
            "get_daily_summary",
            "Daily summary",
            "Per day: sales, downloads and trials, the footer has the totals. Ends today at the latest. " +
                    "Without a range, all days since the first activity are returned (thousands of rows).",
            DateRangeFilter.Table,
            effectiveDateRange = { data, dateRange ->
                val today = YearMonthDay.now()
                val start = maxOf(dateRange.start ?: data.getFirstActivityDate() ?: today, Marketplace.Birthday)
                val end = minOf(dateRange.end ?: today, today)
                require(start <= end) { "The date range must contain a day between ${Marketplace.Birthday.asIsoString} and today" }
                McpDateRange(start, end)
            },
        ) { _, dateRange, _ -> TimeSpanSummaryTable(dateRange.toRange(), "Daily summary") },
        TableTool(
            "get_sales_and_trials_breakdown",
            "Sales and trials breakdown",
            "Sales of the range by license period and customer type with the total, and trials by country. " +
                    "Use the same startDate and endDate for a single day.",
            DateRangeFilter.Table,
        ) { _, dateRange, _ -> DaySummaryTable(dateRange.toRange(), "Sales and trials breakdown") },
        TableTool(
            "get_top_countries",
            "Countries",
            "By country: sales, share of sales, trials, share of trials and trial conversion, sorted by sales. " +
                    "sortBy=trials returns only the trials, including trials without a country. " +
                    "Uses the licenses sold and the trials started in the range, conversions may be after the range.",
            DateRangeFilter.InputData,
            properties = buildJsonObject {
                put("sortBy", enumProperty(listOf("sales", "trials"), "Default: sales"))
            },
        ) { _, _, arguments ->
            when (arguments.choiceArgument("sortBy", listOf("sales", "trials"))) {
                "trials" -> TopTrialCountriesTable(maxItems = null, smallSpaceFormat = false, showEmptyCountry = true)
                else -> TopCountriesTable(smallSpace = false, maxItems = null, showTrials = true)
            }
        },
        TableTool(
            "get_mrr_movements",
            "MRR movements",
            "Experimental. Per month: changes of the MRR by new, expansion, contraction, churned and reactivated customers, and the quick ratio.",
            DateRangeFilter.Rows,
        ) { _, _, _ -> MrrMovementsTable() },
        TableTool(
            "get_revenue_retention",
            "Revenue retention",
            "Experimental. Per month: gross and net revenue retention (GRR, NRR) of the recurring revenue of the customers of 12 months before.",
            DateRangeFilter.Rows,
        ) { _, _, _ -> RevenueRetentionTable() },
        TableTool(
            "get_customer_cohorts",
            "Customer cohorts",
            "Experimental. Customers by the year of their first recurring revenue, and their customer and revenue retention after 1, 2, ... years.",
            DateRangeFilter.Rows,
        ) { _, _, _ -> CohortRetentionTable() },
        TableTool(
            "get_lifetime_value",
            "Realized lifetime value",
            "Experimental. Per cohort year: customers and the revenue they paid so far, in total and per customer.",
            DateRangeFilter.Rows,
        ) { _, _, _ -> RealizedLifetimeValueTable() },
        TableTool(
            "get_annual_renewal_rates",
            "Renewal rates of annual licenses",
            "Experimental. Per year of the end of the subscriptions: share of annual subscriptions renewed, by number of previous renewals.",
            DateRangeFilter.Rows,
        ) { _, _, _ -> RenewalRateTable(LicensePeriod.Annual) },
        TableTool(
            "get_monthly_renewal_rates",
            "Renewal rates of monthly licenses",
            "Experimental. Per month of the end of the subscriptions: share of monthly subscriptions renewed, by number of previous renewals.",
            DateRangeFilter.Rows,
        ) { _, _, _ -> RenewalRateTable(LicensePeriod.Monthly) },
        TableTool(
            "get_average_revenue_per_customer",
            "Average revenue per customer",
            "Experimental. Per month: paying customers and the average recurring revenue per customer (ARPC).",
            DateRangeFilter.Rows,
        ) { _, _, _ -> AverageRevenuePerCustomerTable() },
    )

    val tools: List<McpToolDefinition> = listOf(
        McpToolDefinition(
            "list_plugins",
            "Plugins",
            "The Marketplace account of the server's API key and its plugins. Sales, licenses and trials exist only for paid and freemium plugins. " +
                    "Paid and freemium plugins have their monetization settings: product code, pricing model and the requirements confirmed by the vendor.",
            JsonObject(emptyMap()),
            emptyList(),
        ) { listPlugins() },
    ) + tableTools.map { tool ->
        McpToolDefinition(
            tool.name,
            tool.title,
            tool.description + " Table result.",
            buildJsonObject {
                pluginTableProperties.forEach { (key, value) -> put(key, value) }
                put("currency", currencyProperty)
                tool.properties.forEach { (key, value) -> put(key, value) }
            },
            listOf("pluginId"),
        ) { arguments -> callTableTool(tool, arguments) }
    } + listOf(
        McpToolDefinition(
            "list_sales",
            "Sales",
            "Sales sorted by date, with customer, reseller, amounts and line items (license IDs, validity, discounts), and totals. " +
                    "With groupBy, only the totals per group are returned.",
            buildJsonObject {
                put("pluginId", pluginIdProperty)
                put("refNum", stringProperty("Reference number of a sale"))
                put("customerId", integerProperty("Only sales of this customer"))
                put("licenseId", stringProperty("Only sales with this license"))
                put("country", stringProperty("Country of the customer, name or ISO code"))
                put("customerType", enumProperty(CustomerType.entries.map { it.toolName }, "Type of the customer"))
                put("licensePeriod", enumProperty(LicensePeriod.entries.map { it.toolName }, "License period of the sale"))
                put("saleType", enumProperty(PluginSaleItemType.entries.map { it.toolName }, "Only sales with a line item of this type"))
                put("groupBy", enumProperty(SaleGrouping.entries.map { it.id }, "Return the totals per group instead of the sales"))
                put("startDate", startDateProperty)
                put("endDate", endDateProperty)
                put("currency", currencyProperty)
                put("limit", limitProperty)
                put("offset", offsetProperty)
            },
            listOf("pluginId"),
        ) { arguments -> listSales(arguments) },
        McpToolDefinition(
            "list_customers",
            "Customers",
            "Customers with type, country, first and last day of their licenses, active, churned, transferred and total licenses " +
                    "and total sales. A customer is active if the customer holds a license today. Customers don't churn, licenses do: " +
                    "a license is churned if it ended and no customer renewed it. A transferred license was renewed by another customer, " +
                    "e.g. an organization, and is still valid.",
            buildJsonObject {
                put("pluginId", pluginIdProperty)
                put("status", enumProperty(listOf("all", "active", "inactive"), "Default: all"))
                put("untilFrom", dateProperty("First last day of the licenses of the customers"))
                put("untilTo", dateProperty("Last last day of the licenses of the customers"))
                put("country", stringProperty("Country, name or ISO code"))
                put("customerType", enumProperty(CustomerType.entries.map { it.toolName }, "Type of the customers"))
                put("sortBy", enumProperty(CustomerSorting.entries.map { it.id }, "Default: sales, descending"))
                put("currency", currencyProperty)
                put("limit", limitProperty)
                put("offset", offsetProperty)
            },
            listOf("pluginId"),
        ) { arguments -> listCustomers(arguments) },
        McpToolDefinition(
            "list_licenses",
            "Licenses",
            "Licenses with customer, period, validity, status, number of renewals and total amount. " +
                    "Active licenses are valid today (perpetual licenses are always active). " +
                    "Filter by the last day of validity, e.g. for licenses expiring next month or churned in a month.",
            buildJsonObject {
                put("pluginId", pluginIdProperty)
                put("status", enumProperty(listOf("all", "active", "inactive"), "Default: all"))
                put("licensePeriod", enumProperty(LicensePeriod.entries.map { it.toolName }, "Period of the latest subscription"))
                put("expiresFrom", dateProperty("First last day of validity"))
                put("expiresTo", dateProperty("Last last day of validity"))
                put("customerType", enumProperty(CustomerType.entries.map { it.toolName }, "Type of the customer"))
                put("currency", currencyProperty)
                put("limit", limitProperty)
                put("offset", offsetProperty)
            },
            listOf("pluginId"),
        ) { arguments -> listLicenses(arguments) },
        McpToolDefinition(
            "list_customer_licenses",
            "Licenses of a customer",
            "The licenses bought by a customer with status (active if valid today or perpetual), validity, total amount and purchases. " +
                    "Purchases of the same license by another customer, e.g. after a transfer, are not included.",
            buildJsonObject {
                put("pluginId", pluginIdProperty)
                put("customerId", integerProperty("ID of the customer"))
                put("currency", currencyProperty)
            },
            listOf("pluginId", "customerId"),
        ) { arguments -> listCustomerLicenses(arguments) },
        McpToolDefinition(
            "get_pricing",
            "Prices by country",
            "Current prices by country in the country's currency, by customer type, license period and year of subscription " +
                    "(continuity discount). Countries with the same prices are grouped. Missing periods aren't offered. " +
                    "Plugins without continuity discount only have the price of the 1st year, continuityDiscount tells " +
                    "if the plugin has it and when it was disabled: licenses bought before keep it for their renewals.",
            buildJsonObject {
                put("pluginId", pluginIdProperty)
                put("countryCodes", stringArrayProperty("ISO 3166 codes, e.g. [\"US\",\"DE\"]. Default: all countries (slow on the first call)"))
                put("includeTax", booleanProperty("Also return the prices including VAT"))
            },
            listOf("pluginId"),
        ) { arguments -> getPricing(arguments) },
        McpToolDefinition(
            "get_price_changes",
            "Price changes",
            "Changes of the annual base price in USD by customer type, derived from undiscounted new annual sales in the United States " +
                    "(the API has no price history). A change lists only the customer types whose price changed.",
            pluginTableProperties,
            listOf("pluginId"),
        ) { arguments -> getPriceChanges(arguments) },
        McpToolDefinition(
            "list_plugin_releases",
            "Plugin releases",
            "Releases of the plugin, newest first, with version, date, downloads and compatible builds, " +
                    "and the first release of each major version (majorReleases).",
            buildJsonObject {
                put("pluginId", pluginIdProperty)
                put("channel", stringProperty("Release channel, e.g. eap. Default: stable"))
                put("startDate", startDateProperty)
                put("endDate", endDateProperty)
            },
            listOf("pluginId"),
        ) { arguments -> listPluginReleases(arguments) },
        McpToolDefinition(
            "list_offers",
            "Special offers",
            "Special offers of the plugin, which customers redeem with coupons, newest first: name, start date, " +
                    "discount in percent or free months, license periods, and the number of coupons, active coupons " +
                    "and coupons marked as sent to customers. The coupon codes aren't returned.",
            buildJsonObject {
                put("pluginId", pluginIdProperty)
            },
            listOf("pluginId"),
        ) { arguments -> listOffers(arguments) },
        McpToolDefinition(
            "get_offer_usage",
            "Usage of offers and discounts",
            "The tables of the page Offers: Marketplace (the continuity discounts and the specials of the Marketplace, e.g. " +
                    "student, startup, educational or volume discounts, the names contain the percents), active and inactive offers. Per offer or discount: licenses, sales, " +
                    "customers, paid amount, the discount amount (the amount before the discount minus the paid amount, " +
                    "from the percent of each sale, free licenses have none) and the first and last use. Sales are matched " +
                    "to offers by the name in the discount, reseller discounts are ignored. With a range, only the licenses " +
                    "sold in the range are used. chartMetrics has the metrics of the discount amounts of the offers and discounts for " +
                    "create_chart. Table results.",
            buildJsonObject {
                pluginTableProperties.forEach { (key, value) -> put(key, value) }
                put("currency", currencyProperty)
            },
            listOf("pluginId"),
        ) { arguments -> getOfferUsage(arguments) },
        McpToolDefinition(
            "list_jetbrains_product_releases",
            "Releases of JetBrains products",
            "Versions of a JetBrains product with release date and build number, newest first. " +
                    "majorOnly returns the date of the first release of each major version, e.g. 2025.1.",
            buildJsonObject {
                productProperties(withType = true).forEach { (key, value) -> put(key, value) }
                put("majorOnly", booleanProperty("Only the first release of each major version"))
            },
            emptyList(),
        ) { arguments -> listProductReleases(arguments) },
        McpToolDefinition(
            "get_jetbrains_product_release",
            "Release of a JetBrains product",
            "Metadata of a release of a JetBrains product: version, build, date, type, notes link, license requirement, " +
                    "security flag, and optionally what's new (as text), downloads per platform and patches.",
            buildJsonObject {
                put("productCode", productCodeProperty)
                put("version", stringProperty("Version or build, e.g. 2025.1.1 or 251.25410.129. Default: latest"))
                put("type", releaseTypeProperty)
                put("includeWhatsNew", booleanProperty("Include what's new"))
                put("includeDownloads", booleanProperty("Include downloads and patches"))
            },
            emptyList(),
        ) { arguments -> getProductRelease(arguments) },
        McpToolDefinition(
            "list_jetbrains_products",
            "JetBrains products",
            "JetBrains products with code, name, family, categories, sale status, alternative codes and latest release.",
            buildJsonObject {
                put("category", stringProperty("e.g. IDE, Team tool, .NET tool, Utilities, Language, Visual Studio"))
                put("includeDetails", booleanProperty("Include description, tags, types and links"))
            },
            emptyList(),
        ) { arguments -> listProducts(arguments) },
        McpToolDefinition(
            "get_exchange_rates",
            "Exchange rates",
            "ECB reference rates of a date (closest date with rates, see rateDate): the amount of each currency for 1 unit " +
                    "of the base currency, and the currencies supported by the currency argument.",
            buildJsonObject {
                put("date", dateProperty("Default: latest rates"))
                put("baseCurrency", stringProperty("ISO code. Default: EUR"))
                put("currencies", stringArrayProperty("ISO codes. Default: all"))
            },
            emptyList(),
        ) { arguments -> getExchangeRates(arguments) },
        McpToolDefinition(
            "refresh_data",
            "Refresh data",
            "Drops the cached data, in memory and on disk, so that the next calls load it again (slow). " +
                    "Use it only if data is outdated, e.g. a recent sale is missing.",
            buildJsonObject {
                put("pluginId", integerProperty("Only drop the sales, trials, downloads and releases of this plugin. Default: all data"))
            },
            emptyList(),
            readOnly = false,
        ) { arguments -> refreshData(arguments) },
    ) + McpAnalysisTools(provider).tools + McpChartTool(provider).tools

    /**
     * @return The JSON of the result, e.g. the description of a chart without the image.
     * @throws IllegalArgumentException If the tool doesn't exist or if the arguments are invalid, e.g. unknown arguments.
     */
    suspend fun call(name: String, arguments: JsonObject): JsonObject {
        return tool(name, arguments).call(arguments)
    }

    /**
     * @return The result with its content, e.g. an image.
     * @throws IllegalArgumentException If the tool doesn't exist or if the arguments are invalid, e.g. unknown arguments.
     */
    suspend fun result(name: String, arguments: JsonObject): McpToolResult {
        val tool = tool(name, arguments)
        return tool.result?.invoke(arguments) ?: McpToolResult(tool.call(arguments))
    }

    private fun tool(name: String, arguments: JsonObject): McpToolDefinition {
        val tool = tools.firstOrNull { it.name == name }
            ?: throw IllegalArgumentException("Unknown tool: $name")
        // unknown arguments are rejected, otherwise a misspelled filter would silently return unfiltered data
        val unknown = arguments.keys - tool.properties.keys
        require(unknown.isEmpty()) {
            "Unknown argument ${unknown.joinToString()}, valid arguments: ${tool.properties.keys.joinToString().ifEmpty { "none" }}"
        }
        return tool
    }

    private suspend fun listPlugins(): JsonObject {
        val user = provider.userInfo()
        val plugins = provider.plugins()
        val monetizations = coroutineScope {
            plugins.map { plugin ->
                async {
                    // the plugins are listed even if the settings of a plugin can't be loaded
                    if (plugin.isPaidOrFreemium) runCatching { provider.monetization(plugin) }.getOrNull() else null
                }
            }.awaitAll()
        }
        return buildJsonObject {
            put("account", buildJsonObject {
                put("id", user.id)
                put("name", user.name)
            })
            put("plugins", buildJsonArray {
                for ((plugin, monetization) in plugins.zip(monetizations)) {
                    add(buildJsonObject {
                        put("id", plugin.id)
                        put("xmlId", plugin.xmlId)
                        put("name", plugin.name)
                        put("pricingModel", plugin.pricingModel.toolName)
                        put("isPaidOrFreemium", plugin.isPaidOrFreemium)
                        put("downloads", plugin.downloads)
                        put("rating", plugin.rating)
                        put("link", plugin.link)
                        if (monetization != null) {
                            put("monetization", buildJsonObject {
                                put("productCode", monetization.productCode)
                                put("pricingModel", monetization.pricingModel)
                                put("confirmedRequirements", JsonArray(monetization.confirmedRequirements.map(::JsonPrimitive)))
                            })
                        }
                    })
                }
            })
        }
    }

    private suspend fun callTableTool(tool: TableTool, arguments: JsonObject): JsonObject {
        val plugin = provider.findPlugin(arguments)
        val dateRange = McpDateRange.parse(arguments.stringArgument("startDate"), arguments.stringArgument("endDate"))
        val range = dateRange.toRange()

        val data = withCurrency(provider.pluginData(plugin), arguments)
        val effectiveDateRange = tool.effectiveDateRange(data, dateRange)
        val table = tool.createTable(data, effectiveDateRange, arguments)

        val filterInput = tool.dateRangeFilter == DateRangeFilter.InputData && !dateRange.isUnbounded

        table.init(if (filterInput) data.filterTrials { it.date in range } else data)
        data.getSales()?.forEach { table.process(it) }
        data.getLicenses()?.forEach { license ->
            if (!filterInput || license.sale.date in range) {
                table.process(license)
            }
        }

        val rowFilter = range.takeIf { tool.dateRangeFilter == DateRangeFilter.Rows && !dateRange.isUnbounded }
        return buildJsonObject {
            putPlugin(plugin)
            put("table", tool.title)
            // the range of the returned data, null if it's not limited
            put("startDate", effectiveDateRange.start?.asIsoString)
            put("endDate", effectiveDateRange.end?.asIsoString)
            put("currency", data.exchangeRates.targetCurrency.currencyCode)
            putDataDates(data)
            DataTableJson.toJson(table.renderTable(), rowFilter).forEach { (key, value) -> put(key, value) }
        }
    }

    /**
     * Adds the dates of the latest sale and trial, which show how recent the cached data is.
     */
    private suspend fun JsonObjectBuilder.putDataDates(data: PluginData) {
        data.getSales()?.maxOfOrNull { it.date }?.let { put("latestSaleDate", it.asIsoString) }
        data.getTrials()?.maxOfOrNull { it.date }?.let { put("latestTrialDate", it.asIsoString) }
    }

    private suspend fun listSales(arguments: JsonObject): JsonObject {
        val plugin = provider.findPlugin(arguments)
        val dateRange = McpDateRange.parse(arguments.stringArgument("startDate"), arguments.stringArgument("endDate"))
        val range = dateRange.toRange()
        val refNum = arguments.stringArgument("refNum")?.trim()?.takeIf(String::isNotEmpty)
        val customerId = arguments.intArgument("customerId")
        val licenseId = arguments.stringArgument("licenseId")?.trim()?.takeIf(String::isNotEmpty)
        val customerType = arguments.enumArgument<CustomerType>("customerType")
        val licensePeriod = arguments.enumArgument<LicensePeriod>("licensePeriod")
        val saleType = arguments.enumArgument<PluginSaleItemType>("saleType")
        val grouping = arguments.stringArgument("groupBy")?.trim()?.takeIf(String::isNotEmpty)?.let { value ->
            SaleGrouping.entries.firstOrNull { it.id.equals(value, ignoreCase = true) }
                ?: throw IllegalArgumentException("groupBy must be one of ${SaleGrouping.entries.joinToString { it.id }}, but was '$value'")
        }
        val page = Page.of(arguments)

        val data = withCurrency(provider.pluginData(plugin), arguments)
        val country = countryArgument(data, arguments)
        val sales = (data.getSales() ?: emptyList())
            .filter { sale ->
                sale.date in range
                        && (refNum == null || sale.ref == refNum)
                        && (customerId == null || sale.customer.code == customerId)
                        && (licenseId == null || sale.lineItems.any { licenseId in it.licenseIds })
                        && (country == null || sale.customer.country.equals(country, ignoreCase = true))
                        && (customerType == null || sale.customer.type == customerType)
                        && (licensePeriod == null || sale.licensePeriod == licensePeriod)
                        && (saleType == null || sale.lineItems.any { it.type == saleType })
            }
            .sortedBy { it.date }

        val json = McpRecordsJson(data.exchangeRates)
        return buildJsonObject {
            putPlugin(plugin)
            put("startDate", dateRange.start?.asIsoString)
            put("endDate", dateRange.end?.asIsoString)
            put("currency", data.exchangeRates.targetCurrency.currencyCode)
            putDataDates(data)
            put("totals", json.saleTotals(sales))
            when (grouping) {
                null -> {
                    page.putInfo(this, sales.size)
                    put("sales", JsonArray(page.apply(sales).map(json::sale)))
                }

                else -> put("groups", buildJsonArray {
                    for ((key, items) in sales.groupBy(grouping.key).entries.sortedBy { it.key }) {
                        add(buildJsonObject {
                            put(grouping.id, key)
                            json.saleTotals(items).forEach { (name, value) -> put(name, value) }
                        })
                    }
                })
            }
        }
    }

    private suspend fun listCustomers(arguments: JsonObject): JsonObject {
        val plugin = provider.findPlugin(arguments)
        val status = arguments.choiceArgument("status", listOf("all", "active", "inactive")) ?: "all"
        val untilRange = McpDateRange.parse(arguments.stringArgument("untilFrom"), arguments.stringArgument("untilTo"))
        val customerType = arguments.enumArgument<CustomerType>("customerType")
        val sorting = arguments.choiceArgument("sortBy", CustomerSorting.entries.map { it.id })
            ?.let { id -> CustomerSorting.entries.first { it.id == id } } ?: CustomerSorting.Sales
        val page = Page.of(arguments)

        val data = withCurrency(provider.pluginData(plugin), arguments)
        val country = countryArgument(data, arguments)
        val json = McpRecordsJson(data.exchangeRates)
        val customers = json.customerSummaries(data.getLicenses() ?: emptyList(), YearMonthDay.now())
            .filter { customer ->
                (status == "all" || (status == "active") == customer.isActive)
                        && (untilRange.isUnbounded || (customer.lastLicenseEnd != null && customer.lastLicenseEnd in untilRange.toRange()))
                        && (customerType == null || customer.customer.type == customerType)
                        && (country == null || customer.customer.country.equals(country, ignoreCase = true))
            }
            .sortedWith(sorting.comparator)

        return buildJsonObject {
            putPlugin(plugin)
            put("currency", data.exchangeRates.targetCurrency.currencyCode)
            putDataDates(data)
            put("activeCustomers", customers.count { it.isActive })
            page.putInfo(this, customers.size)
            put("customers", JsonArray(page.apply(customers).map(json::customerSummary)))
        }
    }

    private suspend fun listLicenses(arguments: JsonObject): JsonObject {
        val plugin = provider.findPlugin(arguments)
        val status = arguments.choiceArgument("status", listOf("all", "active", "inactive")) ?: "all"
        val licensePeriod = arguments.enumArgument<LicensePeriod>("licensePeriod")
        val expiryRange = McpDateRange.parse(arguments.stringArgument("expiresFrom"), arguments.stringArgument("expiresTo"))
        val customerType = arguments.enumArgument<CustomerType>("customerType")
        val page = Page.of(arguments)

        val data = withCurrency(provider.pluginData(plugin), arguments)
        val today = YearMonthDay.now()
        val licenses = (data.getLicenses() ?: emptyList())
            .groupBy { it.id }
            .map { (licenseId, purchases) -> LicenseSummary.of(licenseId, purchases.sortedBy { it.sale.date }, today) }
            .filter { license ->
                (status == "all" || (status == "active") == license.isActive)
                        && (licensePeriod == null || license.licensePeriod == licensePeriod)
                        && (expiryRange.isUnbounded || (license.validUntil != null && license.validUntil in expiryRange.toRange()))
                        && (customerType == null || license.customer.type == customerType)
            }
            .sortedWith(compareBy(nullsLast()) { it.validUntil })

        val json = McpRecordsJson(data.exchangeRates)
        return buildJsonObject {
            putPlugin(plugin)
            put("currency", data.exchangeRates.targetCurrency.currencyCode)
            putDataDates(data)
            put("activeLicenses", licenses.count { it.isActive })
            page.putInfo(this, licenses.size)
            put("licenses", JsonArray(page.apply(licenses).map(json::licenseSummary)))
        }
    }

    private suspend fun listCustomerLicenses(arguments: JsonObject): JsonObject {
        val plugin = provider.findPlugin(arguments)
        val customerId = arguments.intArgument("customerId")
            ?: throw IllegalArgumentException("customerId is required")

        val data = withCurrency(provider.pluginData(plugin), arguments)
        val customer = data.getSales()?.firstOrNull { it.customer.code == customerId }?.customer
            ?: data.getTrials()?.firstOrNull { it.customer.code == customerId }?.customer
            ?: throw IllegalArgumentException("Unknown customer ID $customerId of plugin ${plugin.id}")

        val today = YearMonthDay.now()
        val licenses = (data.getLicenses() ?: emptyList())
            .filter { it.sale.customer.code == customerId }
            .groupBy { it.id }
            .map { (licenseId, purchases) -> LicenseSummary.of(licenseId, purchases.sortedBy { it.sale.date }, today) }
            // active licenses first, then the licenses, which expired last
            .sortedWith(compareByDescending<LicenseSummary> { it.isActive }.thenByDescending { it.validUntil })

        val json = McpRecordsJson(data.exchangeRates)
        return buildJsonObject {
            putPlugin(plugin)
            put("customer", json.customer(customer))
            put("currency", data.exchangeRates.targetCurrency.currencyCode)
            put("licenseCount", licenses.size)
            put("activeLicenseCount", licenses.count { it.isActive })
            put("licenses", JsonArray(licenses.map { json.license(it) }))
        }
    }

    private suspend fun getPricing(arguments: JsonObject): JsonObject {
        val plugin = provider.findPlugin(arguments)
        // the plugin data of free plugins has prices, too, but the API has no prices for them
        require(plugin.isPaidOrFreemium) { "Plugin ${plugin.id} has no prices, it's not a paid or freemium plugin" }
        val data = provider.pluginData(plugin)
        val pricing = data.pluginPricing
            ?: throw IllegalArgumentException("Plugin ${plugin.id} has no prices, it's not a paid or freemium plugin")
        val includeTax = arguments.booleanArgument("includeTax") ?: false

        val countryCodes = arguments.stringListArgument("countryCodes")?.map { it.trim().uppercase() }
        val countries = when (countryCodes) {
            null -> data.countries.toList()
            else -> countryCodes.map { code ->
                data.countries.byCountryIsoCode(code) ?: throw IllegalArgumentException("Unknown country code '$code'")
            }
        }

        val json = McpRecordsJson(data.exchangeRates)
        val pricesByCountry = coroutineScope {
            countries.map { country -> async { pricing.getCountryPricing(country.country.isoCode)?.let { country to json.prices(it, includeTax) } } }
                .awaitAll()
                .filterNotNull()
        }

        val info = data.getMarketplacePluginInfo()
        val hasContinuityDiscount = PricingOverviewTable.hasContinuityDiscount(info)
        return buildJsonObject {
            putPlugin(plugin)
            put("includesTax", includeTax)
            if (hasContinuityDiscount != null) {
                put("continuityDiscount", buildJsonObject {
                    put("enabled", hasContinuityDiscount)
                    info?.continuityDiscountDisabledAt?.let { put("disabledAt", it.asIsoString) }
                })
            }
            put("priceGroups", buildJsonArray {
                // countries with the same currency and prices are combined
                val groups = pricesByCountry.groupBy({ (country, prices) -> country.currency.isoCode to prices }, { it.first })
                for ((key, groupCountries) in groups.entries.sortedByDescending { it.value.size }) {
                    add(buildJsonObject {
                        put("currency", key.first)
                        put("countries", buildJsonArray { groupCountries.map { it.country.isoCode }.sorted().forEach { add(it) } })
                        key.second.forEach { (name, value) -> put(name, value) }
                    })
                }
            })
        }
    }

    private suspend fun getPriceChanges(arguments: JsonObject): JsonObject {
        val plugin = provider.findPlugin(arguments)
        val dateRange = McpDateRange.parse(arguments.stringArgument("startDate"), arguments.stringArgument("endDate"))
        val range = dateRange.toRange()

        val sales = provider.pluginData(plugin).getSales() ?: emptyList()
        val changes = PriceChanges.find(sales).filter { it.date in range }
        return buildJsonObject {
            putPlugin(plugin)
            put("startDate", dateRange.start?.asIsoString)
            put("endDate", dateRange.end?.asIsoString)
            put("priceChanges", JsonArray(changes.map(McpReleasesJson::priceChange)))
        }
    }

    private suspend fun listPluginReleases(arguments: JsonObject): JsonObject {
        val plugin = provider.findPlugin(arguments)
        val dateRange = McpDateRange.parse(arguments.stringArgument("startDate"), arguments.stringArgument("endDate"))
        val range = dateRange.toRange()
        val channel = arguments.stringArgument("channel")?.trim()
            ?.takeUnless { it.isEmpty() || it.equals("stable", ignoreCase = true) }
            ?: PluginChannelNames.Stable

        val releases = provider.pluginReleases(plugin, channel)
        // major releases are found with all releases, a release of the range may belong to an earlier major version
        val majorReleases = MajorReleases.find(releases).filter { it.date in range }
        val releasesInRange = releases.filter { YearMonthDay.of(it.createdTimestamp) in range }
        return buildJsonObject {
            putPlugin(plugin)
            put("channel", channel.ifEmpty { "stable" })
            put("startDate", dateRange.start?.asIsoString)
            put("endDate", dateRange.end?.asIsoString)
            put("majorReleases", JsonArray(majorReleases.map(McpReleasesJson::majorRelease)))
            put("releaseCount", releasesInRange.size)
            put("releases", JsonArray(releasesInRange.map(McpReleasesJson::pluginRelease)))
        }
    }

    private suspend fun getOfferUsage(arguments: JsonObject): JsonObject {
        val plugin = provider.findPlugin(arguments)
        require(plugin.isPaidOrFreemium) { "Plugin ${plugin.id} has no special offers, it's not a paid or freemium plugin" }
        val source = object : OfferSource {
            override suspend fun offers(pluginId: PluginId): List<PluginOffer> = provider.offers(plugin)

            override suspend fun offerCoupons(pluginId: PluginId, offer: PluginOfferId): List<PluginOfferCoupon> {
                return provider.offerCoupons(plugin, offer)
            }
        }
        // the licenses of the range are used, like the table of the countries
        val tables = OffersTableKind.entries.map { kind ->
            callTableTool(TableTool("get_offer_usage", kind.title, "", DateRangeFilter.InputData) { _, _, _ -> OffersTable(kind, source) }, arguments)
        }
        val sharedKeys = listOf("pluginId", "pluginName", "startDate", "endDate", "currency", "latestSaleDate", "latestTrialDate")
        val licenses = provider.pluginData(plugin).getLicenses() ?: emptyList()
        return buildJsonObject {
            sharedKeys.forEach { key -> tables.first()[key]?.let { put(key, it) } }
            put("tables", JsonArray(tables.map { table -> JsonObject(table.filterKeys { it !in sharedKeys }) }))
            put("chartMetrics", JsonArray(ChartMetricValues.discountMetrics(licenses).map { metric ->
                buildJsonObject {
                    put("metric", metric.id)
                    put("discount", metric.discountName)
                }
            }))
        }
    }

    private suspend fun listOffers(arguments: JsonObject): JsonObject {
        val plugin = provider.findPlugin(arguments)
        require(plugin.isPaidOrFreemium) { "Plugin ${plugin.id} has no special offers, it's not a paid or freemium plugin" }
        val offers = provider.offers(plugin).sortedByDescending { it.startDate }
        val coupons = coroutineScope { offers.map { offer -> async { provider.offerCoupons(plugin, offer.id) } }.awaitAll() }
        return buildJsonObject {
            putPlugin(plugin)
            put("offers", JsonArray(offers.zip(coupons) { offer, offerCoupons ->
                buildJsonObject {
                    put("id", offer.id)
                    put("name", offer.name)
                    put("startDate", offer.startDate.asIsoString)
                    put("discountPercent", offer.discountPercent)
                    put("freeMonths", offer.freePeriodInMonths)
                    put("licensePeriods", JsonArray(offer.licensePeriods.map { JsonPrimitive(it.toolName) }))
                    // the codes of the coupons aren't returned, they are valid discounts
                    put("coupons", offerCoupons.size)
                    put("activeCoupons", offerCoupons.count(PluginOfferCoupon::isActive))
                    put("sentCoupons", offerCoupons.count(PluginOfferCoupon::isSent))
                }
            }))
        }
    }

    private suspend fun listProductReleases(arguments: JsonObject): JsonObject {
        val product = productArgument(arguments)
        val dateRange = McpDateRange.parse(arguments.stringArgument("startDate"), arguments.stringArgument("endDate"))
        val range = dateRange.toRange()
        val type = releaseTypeArgument(arguments)
        val majorOnly = arguments.booleanArgument("majorOnly") ?: false

        val allReleases = provider.productReleases(product, type)
        requireKnownProduct(product, allReleases.isNotEmpty())
        if (majorOnly) {
            val majorReleases = IdeMajorReleases.find(allReleases).filter { it.date in range }.sortedByDescending { it.date }
            return buildJsonObject {
                putProduct(product)
                put("type", type.jsonId)
                put("startDate", dateRange.start?.asIsoString)
                put("endDate", dateRange.end?.asIsoString)
                put("majorReleases", JsonArray(majorReleases.map(McpReleasesJson::majorRelease)))
            }
        }
        val releases = allReleases
            .filter { YearMonthDay.of(it.date.toJavaLocalDate()) in range }
            .sortedByDescending { it.date }
        return buildJsonObject {
            putProduct(product)
            put("type", type.jsonId)
            put("startDate", dateRange.start?.asIsoString)
            put("endDate", dateRange.end?.asIsoString)
            put("releaseCount", releases.size)
            put("releases", JsonArray(releases.map(McpReleasesJson::productRelease)))
        }
    }

    private suspend fun getProductRelease(arguments: JsonObject): JsonObject {
        val product = productArgument(arguments)
        val type = releaseTypeArgument(arguments)
        val version = arguments.stringArgument("version")?.trim()?.takeIf(String::isNotEmpty)
        val includeWhatsNew = arguments.booleanArgument("includeWhatsNew") ?: false
        val includeDownloads = arguments.booleanArgument("includeDownloads") ?: false

        val releases = provider.productReleases(product, type)
        requireKnownProduct(product, releases.isNotEmpty())
        val release = when (version) {
            null -> releases.maxByOrNull { it.date }
            else -> releases.firstOrNull { it.version == version || it.build?.toBuilderNumberString() == version }
        } ?: throw IllegalArgumentException(
            when (version) {
                null -> "No release of type ${type.jsonId} found for product ${product.code}"
                else -> "Version $version of type ${type.jsonId} not found for product ${product.code}, see list_jetbrains_product_releases"
            }
        )

        return buildJsonObject {
            putProduct(product)
            put("release", McpReleasesJson.productReleaseDetails(release, includeWhatsNew, includeDownloads))
        }
    }

    private suspend fun listProducts(arguments: JsonObject): JsonObject {
        val category = arguments.stringArgument("category")?.trim()?.takeIf(String::isNotEmpty)
        val includeDetails = arguments.booleanArgument("includeDetails") ?: false
        val products = provider.products().productsWithReleases
            .filter { product -> category == null || product.categories?.any { it.id.equals(category, ignoreCase = true) } == true }
            .sortedBy { it.name }
        return buildJsonObject {
            put("productCount", products.size)
            put("products", JsonArray(products.map { McpReleasesJson.product(it, includeDetails) }))
        }
    }

    /**
     * Rejects unknown product codes, if the product has no releases. Without the check, an agent wouldn't notice a typo.
     */
    private suspend fun requireKnownProduct(product: JetBrainsProductCode, hasReleases: Boolean) {
        if (hasReleases) {
            return
        }
        val isKnown = provider.products().productsWithReleases.any { it.code.code == product.code }
        require(isKnown) { "Unknown product code ${product.code}, see list_jetbrains_products" }
    }

    private suspend fun getExchangeRates(arguments: JsonObject): JsonObject {
        val table = provider.exchangeRateTable()
        val requestedDate = McpDateRange.parseDate("date", arguments.stringArgument("date"))
        val date = requestedDate?.toLocalDate() ?: table.lastDate
            ?: throw IllegalStateException("No ECB exchange rates available")
        val rateDate = table.closestDate(date)
            ?: throw IllegalStateException("No ECB exchange rates available")
        // the closest date is used for weekends and holidays, but not for dates before the available rates or in the future
        require(date >= table.firstDate!!.minusDays(MAX_RATE_DATE_DISTANCE_DAYS) && date <= LocalDate.now()) {
            "No ECB exchange rates available for $date, rates are available from ${table.firstDate} until today"
        }

        val baseCurrency = arguments.stringArgument("baseCurrency")?.trim()?.uppercase()?.takeIf(String::isNotEmpty) ?: "EUR"
        val baseRate = table.euroRate(baseCurrency, rateDate)
            ?: throw IllegalArgumentException("No ECB exchange rate of $baseCurrency on ${rateDate}, available: ${availableCurrencies(table, rateDate).joinToString()}")

        val currencies = arguments.stringListArgument("currencies")?.map { it.trim().uppercase() }
            ?: availableCurrencies(table, rateDate).filter { it != baseCurrency }
        return buildJsonObject {
            put("date", requestedDate?.asIsoString)
            put("rateDate", rateDate.toString())
            put("baseCurrency", baseCurrency)
            put("rates", buildJsonObject {
                for (currency in currencies) {
                    val rate = table.euroRate(currency, rateDate)
                        ?: throw IllegalArgumentException("No ECB exchange rate of $currency on ${rateDate}, available: ${availableCurrencies(table, rateDate).joinToString()}")
                    put(currency, if (baseCurrency == "EUR") rate else rate.divide(baseRate, RATE_PRECISION))
                }
            })
            put("availableCurrencies", buildJsonArray { availableCurrencies(table, table.lastDate!!).forEach { add(it) } })
        }
    }

    /**
     * @return The plugin data with amounts converted into the currency of the argument `currency`, if it's set.
     */
    private suspend fun withCurrency(data: PluginData, arguments: JsonObject): PluginData {
        val currency = arguments.stringArgument("currency")?.trim()?.uppercase()?.takeIf(String::isNotEmpty)
            ?: return data
        if (currency == data.exchangeRates.targetCurrency.currencyCode) {
            return data
        }

        // amounts of recent sales are converted with the latest rates, the currency must still have rates
        val table = provider.exchangeRateTable()
        val lastDate = table.lastDate ?: throw IllegalStateException("No ECB exchange rates available")
        val available = availableCurrencies(table, lastDate)
        require(currency in available) { "Unsupported currency '$currency', supported currencies: ${available.joinToString()}" }
        return data.copy(exchangeRates = provider.exchangeRates(currency))
    }

    private fun availableCurrencies(table: EcbRateTable, date: LocalDate): List<String> {
        return (table.currencies + "EUR").filter { table.euroRate(it, date) != null }.sorted()
    }

    private fun refreshData(arguments: JsonObject): JsonObject {
        val plugin = arguments["pluginId"]?.takeUnless { it is JsonNull }?.let { provider.findPlugin(arguments) }
        provider.dropCachedData(plugin)
        return buildJsonObject {
            put("refreshed", true)
            plugin?.let { putPlugin(it) }
            put("message", "The cached data was dropped, it's loaded again by the next calls.")
        }
    }

    private fun countryArgument(data: PluginData, arguments: JsonObject): String? {
        // customers have the names of the countries, ISO codes are mapped to the names
        return arguments.stringArgument("country")?.trim()?.let { value ->
            data.countries.byCountryIsoCode(value.uppercase())?.country?.printableName ?: value
        }
    }

    private fun releaseTypeArgument(arguments: JsonObject): ProductReleaseType {
        val value = arguments.stringArgument("type")?.trim()?.lowercase()?.takeIf(String::isNotEmpty)
            ?: return ProductReleaseType.Release
        return ProductReleaseType.entries.firstOrNull { it.jsonId == value }
            ?: throw IllegalArgumentException("type must be one of ${ProductReleaseType.entries.joinToString { it.jsonId }}, but was '$value'")
    }

    internal companion object {
        const val MAX_RATE_DATE_DISTANCE_DAYS = 7L

        val RATE_PRECISION = MathContext(10)

        val currencyProperty = stringProperty("ISO code of the currency of the amounts, see get_exchange_rates. Default: the server's currency")

        val pluginIdProperty = integerProperty("ID of the plugin, see list_plugins")

        val startDateProperty = dateProperty("First day, inclusive")

        val endDateProperty = dateProperty("Last day, inclusive")

        val limitProperty = integerProperty("Maximum number of items. Default: ${Page.DEFAULT_LIMIT}, maximum ${Page.MAX_LIMIT}")

        val offsetProperty = integerProperty("Number of items to skip. Default: 0")

        val pluginTableProperties = buildJsonObject {
            put("pluginId", pluginIdProperty)
            put("startDate", startDateProperty)
            put("endDate", endDateProperty)
        }

        val productCodeProperty = stringProperty("Product code, e.g. IIU (IntelliJ IDEA), PS, PCP, see list_jetbrains_products. Default: IIU")

        val releaseTypeProperty = enumProperty(ProductReleaseType.entries.map { it.jsonId }, "Default: release")

        fun productProperties(withType: Boolean) = buildJsonObject {
            put("productCode", productCodeProperty)
            if (withType) {
                put("type", releaseTypeProperty)
            }
            put("startDate", startDateProperty)
            put("endDate", endDateProperty)
        }

        fun stringProperty(description: String) = property("string", description)

        fun integerProperty(description: String) = property("integer", description)

        fun booleanProperty(description: String) = property("boolean", description)

        fun dateProperty(description: String) = buildJsonObject {
            put("type", "string")
            put("format", "date")
            put("description", description)
        }

        fun enumProperty(values: List<String>, description: String? = null) = buildJsonObject {
            put("type", "string")
            put("enum", buildJsonArray { values.forEach { add(it) } })
            description?.let { put("description", it) }
        }

        fun stringArrayProperty(description: String) = buildJsonObject {
            put("type", "array")
            put("items", buildJsonObject { put("type", "string") })
            put("description", description)
        }

        private fun property(type: String, description: String) = buildJsonObject {
            put("type", type)
            put("description", description)
        }
    }
}
