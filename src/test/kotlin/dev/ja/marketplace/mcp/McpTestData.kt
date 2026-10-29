/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.mcp

import dev.ja.marketplace.ChartData
import dev.ja.marketplace.CustomEvent
import dev.ja.marketplace.client.LicenseInfo
import dev.ja.marketplace.client.MarketplaceClient
import dev.ja.marketplace.client.PluginChannel
import dev.ja.marketplace.client.PluginId
import dev.ja.marketplace.client.PluginReleaseId
import dev.ja.marketplace.client.SalesWithLicensesInfo
import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.client.currency.CurrencyInfo
import dev.ja.marketplace.client.currency.MarketplaceCurrencies
import dev.ja.marketplace.client.model.*
import dev.ja.marketplace.data.PluginData
import dev.ja.marketplace.data.PluginPricing
import dev.ja.marketplace.data.chart.ChartMetricValues
import dev.ja.marketplace.exchangeRate.EcbExchangeRateData
import dev.ja.marketplace.exchangeRate.EcbRateTable
import dev.ja.marketplace.exchangeRate.ExchangeRates
import dev.ja.marketplace.services.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.datetime.LocalDate
import org.javamoney.moneta.FastMoney
import java.lang.reflect.Proxy
import java.math.BigDecimal
import kotlin.time.Instant

/**
 * Plugin data for the tests of the MCP tools, the amounts are in USD to avoid loading exchange rates.
 */
object McpTestData {
    const val PLUGIN_ID = 1000

    // like the API, the customers have the names of their countries
    private val individualDE = CustomerInfo(1, "Germany", CustomerType.Individual, "Individual DE")
    private val organizationUS = CustomerInfo(2, Country.UNITED_STATES, CustomerType.Organization, "Organization US")
    private val individualUS = CustomerInfo(3, Country.UNITED_STATES, CustomerType.Individual, "Individual US")
    private val individualFR = CustomerInfo(4, "France", CustomerType.Individual, "Individual FR")

    val offers = listOf(
        PluginOffer(1, "Launch offer", PLUGIN_ID, YearMonthDay(2025, 3, 1), listOf("Annual"), discountPercent = 50),
        PluginOffer(2, "Free months", PLUGIN_ID, YearMonthDay(2025, 9, 1), listOf("Monthly", "Annual"), freePeriodInMonths = 3),
    )

    val offerCoupons = mapOf(
        1 to listOf(
            PluginOfferCoupon("CODE-1", isActive = true, isSent = true),
            PluginOfferCoupon("CODE-2", isActive = false, isSent = true),
            PluginOfferCoupon("CODE-3", isActive = true, isSent = false),
        ),
    )

    /**
     * A sale with manual changes by JetBrains: its total is 0, its line items have the regular amounts and no discounts.
     */
    fun changedSale(ref: String, date: YearMonthDay): PluginSale {
        val amount = FastMoney.of(68, MarketplaceCurrencies.USD)
        val zero = FastMoney.zero(MarketplaceCurrencies.USD)
        val validity = date.rangeTo(date.add(1, 0, -1))
        val lineItems = listOf("a", "b").map { license ->
            SubscriptionPluginSaleItem(PluginSaleItemType.New, listOf("license-$ref-$license"), amount, amount, emptyList(), validity)
        }
        return PluginSale(ref, date, zero, zero, LicensePeriod.Annual, individualUS, null, lineItems)
    }

    val plugin = PluginInfoSummary(
        id = PLUGIN_ID,
        xmlId = "dev.ja.test",
        name = "Test Plugin",
        link = "/plugin/$PLUGIN_ID-test-plugin",
        previewText = "",
        downloads = 100,
        pricingModel = PluginPricingModel.Paid,
        rating = 4.5,
        hasSource = false,
    )

    val sales = listOf(
        sale("ref-1", YearMonthDay(2024, 3, 10), LicensePeriod.Annual, individualDE, 100),
        sale("ref-2", YearMonthDay(2024, 11, 5), LicensePeriod.Annual, organizationUS, 300),
        sale("ref-3", YearMonthDay(2025, 2, 1), LicensePeriod.Monthly, individualUS, 10),
    )

    val trials = listOf(
        PluginTrial("trial-1", YearMonthDay(2024, 3, 1), individualDE),
        PluginTrial("trial-2", YearMonthDay(2025, 1, 20), individualFR),
    )

    val pluginReleases = listOf(
        pluginRelease(3, "2.1.0", "2025-06-01T10:00:00Z"),
        pluginRelease(2, "2.0.0", "2025-01-15T10:00:00Z"),
        pluginRelease(1, "1.0.0", "2024-02-01T10:00:00Z"),
    )

    val productReleases = listOf(
        productRelease("2024.3", "2024.3", LocalDate(2024, 11, 14)),
        productRelease("2025.1", "2025.1", LocalDate(2025, 4, 16)),
        productRelease("2025.1", "2025.1.1", LocalDate(2025, 5, 2)),
    )

    private val countries = Countries(
        listOf(
            country("US", Country.UNITED_STATES, "USD"),
            country("DE", "Germany", "EUR"),
            country("FR", "France", "EUR"),
        )
    )

    /**
     * Rates of the ECB for the days of the sales, 1 EUR is 2 USD. CYP is a discontinued currency without recent rates.
     * 2024-03-10 and 2025-02-01 are on weekends, the rates of the closest days are used.
     */
    val ecbRates: EcbRateTable = EcbRateTable.parseCsv(
        """
        Date,USD,JPY,CYP,
        2024-03-08,2.0,160,0.5,
        2024-11-05,2.0,165,,
        2025-01-31,2.0,170,,
        """.trimIndent().byteInputStream()
    )

    val reviews = listOf(
        review(1, "2025-01-10T10:00:00Z", 5),
        review(2, "2025-01-20T10:00:00Z", 3),
        review(3, "2025-06-01T10:00:00Z", 1),
        review(4, "2025-06-02T10:00:00Z", 1, spam = true),
        // don't count for the rating: a resolved review and a comment without rating
        review(5, "2025-01-25T10:00:00Z", 1, resolveState = "RESOLVED"),
        review(6, "2025-01-26T10:00:00Z", 0, subRating = mapOf(PerformanceRating to 4)),
    )

    /** Number of calls of [McpDataProvider.dropCachedData]. */
    var droppedCaches = 0

    val provider: McpDataProvider = TestProvider(withPricing = false)

    /**
     * Provides plugin data with prices.
     */
    val providerWithPricing: McpDataProvider = TestProvider(withPricing = true)

    /**
     * @return A provider of plugin data with other sales, e.g. for price changes.
     */
    fun providerWithSales(sales: List<PluginSale>): McpDataProvider = TestProvider(withPricing = false, sales)

    /**
     * @return A provider of plugin data with prices and the info of the plugin, e.g. without continuity discount.
     */
    fun providerWithPricing(info: MarketplacePluginInfo, withRenewalPrices: Boolean): McpDataProvider {
        return TestProvider(withPricing = true, marketplacePluginInfo = info, withRenewalPrices = withRenewalPrices)
    }

    private class TestProvider(
        private val withPricing: Boolean,
        private val sales: List<PluginSale> = McpTestData.sales,
        private val marketplacePluginInfo: MarketplacePluginInfo? = null,
        private val withRenewalPrices: Boolean = true,
    ) : McpDataProvider {
        override suspend fun userInfo(): UserInfo = UserInfo("user-1", "Test User")

        override fun plugins(): List<PluginInfoSummary> = listOf(plugin)

        override suspend fun pluginData(plugin: PluginInfoSummary): PluginData {
            return pluginData(withPricing, sales, marketplacePluginInfo, withRenewalPrices)
        }

        override suspend fun pluginReleases(plugin: PluginInfoSummary, channel: PluginChannel): List<PluginReleaseInfo> {
            return pluginReleases.filter { it.channel == channel }
        }

        override suspend fun pluginChannels(plugin: PluginInfoSummary): List<PluginChannel> = listOf("", "eap")

        override suspend fun verificationResults(release: PluginReleaseId, type: PluginVerificationType): List<PluginVerificationResult> {
            // like the API, the reports of the Plugin Verifier have a path, those of the performance an address
            val performance = type == PluginVerificationType.IdePerformance
            val resultType = if (performance) "OK" else "WARNINGS"
            return listOf(
                PluginVerificationResult(
                    resultId = release.toLong(),
                    pluginReleaseId = release,
                    verificationType = type.requestParameter,
                    resultType = resultType,
                    verificationVerdict = if (performance) "No issues" else "Compatible. 2 usages of deprecated API",
                    verificationDateMillis = 1748772000000,
                    product = VerifiedProduct(
                        "IDEA",
                        productName = "IntelliJ IDEA",
                        ideVersion = "IU-251.25410.109",
                        shortPresentableVersion = "2025.1.1 (251.25410.109)",
                        fullPresentableVersion = "IntelliJ IDEA 2025.1.1 (251.25410.109)",
                    ),
                    fullVerificationResultUrl = if (performance) "https://example.com/report/$release" else "/files/1/$release/verifier.json",
                )
            )
        }

        override suspend fun offers(plugin: PluginInfoSummary): List<PluginOffer> = offers

        override suspend fun offerCoupons(plugin: PluginInfoSummary, offer: PluginOfferId): List<PluginOfferCoupon> {
            return offerCoupons[offer] ?: emptyList()
        }

        override suspend fun monetization(plugin: PluginInfoSummary): PluginMonetization {
            return PluginMonetization("PTEST", "PAID", listOf("OBFUSCATE"))
        }

        override suspend fun productReleases(product: JetBrainsProductCode, type: ProductReleaseType): List<ProductRelease> {
            return productReleases.filter { product == JetBrainsProductCode.IntelliJIDEAUltimate && it.type == type }
        }

        override suspend fun products(): Products = products

        override fun dropCachedData(plugin: PluginInfoSummary?) {
            droppedCaches++
        }

        override fun exchangeRates(currencyCode: String?): ExchangeRates {
            return ExchangeRates(currencyCode ?: "USD", EcbExchangeRateData(downloader = { ecbRates }))
        }

        override suspend fun exchangeRateTable(): EcbRateTable = ecbRates

        override suspend fun groupedDownloads(
            plugin: PluginInfoSummary,
            dimension: DownloadRequestDimension,
            groupType: DownloadRequestDimension,
            countType: DownloadCountType,
            startDate: YearMonthDay?,
            vararg filters: DownloadFilter,
        ): GroupedDownloadResponse {
            val product = filters.firstOrNull { it.type == DownloadFilterType.ProductCode }?.value
            val groups = when (groupType) {
                DownloadRequestDimension.ProductCode -> listOf(
                    downloadGroup("IIU", "IntelliJ IDEA", "2025-08-01" to 10, "2025-09-01" to 20),
                    downloadGroup("PCP", "PyCharm", "2025-08-01" to 5),
                    downloadGroup("unknown", null, "2025-09-01" to 1),
                ).filter { product == null || it.name == product }

                else -> when (product) {
                    "IIU" -> listOf(downloadGroup("2", null, "2025-08-01" to 6, "2025-09-01" to 20), downloadGroup("3", null, "2025-08-01" to 4))
                    "PCP" -> listOf(downloadGroup("3", null, "2025-08-01" to 5))
                    else -> listOf(downloadGroup("2", null, "2025-08-01" to 6, "2025-09-01" to 20), downloadGroup("3", null, "2025-08-01" to 9))
                }
            }
            return GroupedDownloadResponse(countType.requestPathSegment, dimension.requestPathSegment, groupType.requestPathSegment, groups)
        }

        override suspend fun reviews(plugin: PluginInfoSummary): List<PluginReviewComment> = reviews

        override suspend fun pageAnalytics(plugin: PluginInfoSummary, timeFrame: PageAnalyticsTimeFrame): PluginPageAnalytics {
            return PluginPageAnalytics(
                days = listOf(PageAnalyticsDay("20250102", 3, 2, 5), PageAnalyticsDay("20250101", 1, 1, 2)),
                countries = listOf(PageAnalyticsCountry("Germany", 1, 1), PageAnalyticsCountry("United States", 3, 2)),
                sources = listOf(PageAnalyticsSource("(direct)", 3), PageAnalyticsSource("google", 1)),
                isReliable = false,
            )
        }

        override suspend fun chartValues(plugin: PluginInfoSummary?): ChartMetricValues {
            requireNotNull(plugin) { "pluginId is required, the summary of all plugins is disabled" }
            return ChartData().values(pluginData(plugin))
        }

        override fun customEvents(plugins: Collection<PluginId>): List<CustomEvent> {
            return listOf(CustomEvent("launch", YearMonthDay(2025, 1, 15), "Launch", PLUGIN_ID)).filter { it.plugin in plugins }
        }
    }

    val products = Products(
        listOf(
            ProductWithReleases(
                code = JetBrainsProductCode.IntelliJIDEAUltimate,
                name = "IntelliJ IDEA Ultimate",
                categories = listOf(IdeCategory),
                releases = productReleases,
                forSale = true,
                additionalLinks = null,
            ),
            ProductWithReleases(
                code = JetBrainsProductCode.byProductCode("TC"),
                name = "TeamCity",
                categories = listOf(TeamToolCategory),
                releases = emptyList(),
                additionalLinks = null,
            ),
        )
    )

    fun pluginData(
        withPricing: Boolean = false,
        sales: List<PluginSale> = McpTestData.sales,
        marketplacePluginInfo: MarketplacePluginInfo? = null,
        /** If the prices have a 2nd and 3rd year, the prices of plugins without continuity discount haven't. */
        withRenewalPrices: Boolean = true,
        /** A free plugin has no sales, licenses, trials and prices, like the data of [dev.ja.marketplace.PluginDataLoader]. */
        free: Boolean = false,
        downloadsMonthly: List<MonthlyDownload> = listOf(
            MonthlyDownload(YearMonthDay(2024, 3, 1), 40),
            MonthlyDownload(YearMonthDay(2025, 1, 1), 60),
        ),
        downloadsDaily: List<DailyDownload> = listOf(
            DailyDownload(YearMonthDay(2024, 3, 1), 40),
            DailyDownload(YearMonthDay(2025, 1, 20), 60),
        ),
    ): PluginData {
        val pricingModel = if (free) PluginPricingModel.Free else PluginPricingModel.Paid
        val pluginInfo = PluginInfo(
            id = PLUGIN_ID,
            name = plugin.name,
            link = plugin.link,
            approve = true,
            xmlId = plugin.xmlId,
            description = "",
            customIdeList = false,
            family = IntelliJFamily,
            downloads = 100,
            hasUnapprovedUpdate = false,
            pricingModel = pricingModel,
            isHidden = false,
        )

        return PluginData(
            countries,
            ExchangeRates("USD", EcbExchangeRateData()),
            plugin.copy(pricingModel = pricingModel),
            CompletableDeferred(pluginInfo),
            CompletableDeferred(PluginRating(0, 0, 0.0, emptyMap())),
            CompletableDeferred(100L),
            CompletableDeferred(downloadsMonthly),
            CompletableDeferred(downloadsDaily),
            if (withPricing && !free) PluginPricing.create(pricingClient(withRenewalPrices), PLUGIN_ID, countries) else null,
            if (free) null else CompletableDeferred(SalesWithLicensesInfo(sales, LicenseInfo.createFrom(sales))),
            if (free) null else CompletableDeferred(trials),
            if (free) null else CompletableDeferred(marketplacePluginInfo),
        )
    }

    /**
     * A new annual license of an organization in the United States without a discount, which pays the base price.
     */
    fun basePriceSale(customerId: Int, date: YearMonthDay, amountUSD: Int): PluginSale {
        return sale("base-$customerId", date, LicensePeriod.Annual, CustomerInfo(customerId, Country.UNITED_STATES, CustomerType.Organization), amountUSD)
    }

    private fun sale(ref: String, date: YearMonthDay, period: LicensePeriod, customer: CustomerInfo, amountUSD: Int): PluginSale {
        val amount = FastMoney.of(amountUSD, MarketplaceCurrencies.USD)
        val validity = when (period) {
            LicensePeriod.Monthly -> date.rangeTo(date.add(0, 1, -1))
            else -> date.rangeTo(date.add(1, 0, -1))
        }
        val lineItem = SubscriptionPluginSaleItem(PluginSaleItemType.New, listOf("license-$ref"), amount, amount, emptyList(), validity)
        return PluginSale(ref, date, amount, amount, period, customer, null, listOf(lineItem))
    }

    /**
     * A client, which only provides the prices: annual prices in Germany and France, annual, monthly and perpetual prices in the United States.
     */
    private fun pricingClient(withRenewalPrices: Boolean): MarketplaceClient {
        return Proxy.newProxyInstance(javaClass.classLoader, arrayOf(MarketplaceClient::class.java)) { _, method, args ->
            when (method.name) {
                "priceInfo" -> when (args[1]) {
                    "DE", "FR" -> priceInfo(
                        "EUR",
                        PriceInfoByPeriod(
                            annual = when {
                                withRenewalPrices -> PriceInfoTypeData(price("50", "59.50"), price("40", "47.60"), price("30", "35.70"))
                                else -> PriceInfoTypeData(price("50", "59.50"))
                            },
                        ),
                    )

                    else -> priceInfo(
                        "USD",
                        PriceInfoByPeriod(
                            annual = PriceInfoTypeData(price("50", null)),
                            monthly = PriceInfoTypeData(price("5", null)),
                            perpetual = price("150", null),
                        ),
                    )
                }

                else -> throw UnsupportedOperationException(method.name)
            }
        } as MarketplaceClient
    }

    private fun priceInfo(currency: String, personal: PriceInfoByPeriod): PluginPriceInfo {
        val commercial = PriceInfoByPeriod(annual = PriceInfoTypeData(price("100", null)))
        return PluginPriceInfo("", "", CurrencyInfo(currency, currency, false), PluginPriceInfoByType(personal, commercial))
    }

    private fun price(price: String, priceTaxed: String?): PriceInfoData {
        return PriceInfoData(BigDecimal(price), priceTaxed?.let(::BigDecimal), "shop-code")
    }

    private fun country(isoCode: String, name: String, currency: String): CountryWithCurrency {
        return CountryWithCurrency(Country(isoCode, name, name, true, "", ""), Currency(currency, currency, false))
    }

    private fun pluginRelease(id: Int, version: String, timestamp: String): PluginReleaseInfo {
        return PluginReleaseInfo(
            id = id,
            pluginId = PLUGIN_ID,
            updateInfoUrlPath = "",
            version = version,
            approve = true,
            listed = true,
            hidden = false,
            createdTimestamp = Instant.parse(timestamp),
            fileUrlPath = "",
            fileSizeBytes = 1000,
            channel = "",
            downloads = 10 * id,
            releaseVersion = version,
        )
    }

    private fun productRelease(majorVersion: String, version: String, date: LocalDate): ProductRelease {
        return ProductRelease(
            date = date,
            type = ProductReleaseType.Release,
            majorVersion = majorVersion,
            version = version,
            patches = emptyMap(),
        )
    }

    private fun downloadGroup(name: String, comment: String?, vararg values: Pair<String, Long>): DownloadResponseGroup {
        return DownloadResponseGroup(name, comment, values.map { (date, value) -> DownloadResponseItem(date, value) })
    }

    private fun review(
        id: Long,
        timestamp: String,
        rating: Int,
        spam: Boolean = false,
        resolveState: String = "INIT",
        subRating: Map<PluginRatingCategory, Int>? = null,
    ): PluginReviewComment {
        return PluginReviewComment(
            id = id,
            createdTimestamp = Instant.parse(timestamp),
            comment = "Review $id",
            pluginId = PLUGIN_ID,
            rating = rating.toShort(),
            repliesCount = 0,
            vendor = false,
            markedAsSpam = spam,
            author = JetBrainsAccountInfo("author-$id", "Author $id"),
            resolveState = resolveState,
            subRating = subRating,
        )
    }
}
