/*
 * Copyright (c) 2023-2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace

import dev.ja.marketplace.churn.ChurnValidities
import dev.ja.marketplace.churn.LicenseChurnProcessor
import dev.ja.marketplace.churn.isCountedByChurn
import dev.ja.marketplace.client.*
import dev.ja.marketplace.client.model.*
import dev.ja.marketplace.data.PluginData
import dev.ja.marketplace.data.DataTable
import dev.ja.marketplace.data.LinkedChurnRate
import dev.ja.marketplace.data.MarketplaceDataTableFactory
import dev.ja.marketplace.data.RenderedDataTable
import dev.ja.marketplace.data.chargeOverview.ChargeOverviewTableFactory
import dev.ja.marketplace.data.chart.ChartDetail
import dev.ja.marketplace.data.chart.ChartDownloadsSource
import dev.ja.marketplace.data.chart.ChartMetricFamily
import dev.ja.marketplace.data.chart.ChartMetricValues
import dev.ja.marketplace.data.chart.ChartWindow
import dev.ja.marketplace.data.customerType.CustomerTypeFactory
import dev.ja.marketplace.data.customers.CustomerTable
import dev.ja.marketplace.data.customers.CustomerTableFactory
import dev.ja.marketplace.data.daySummary.DaySummaryFactory
import dev.ja.marketplace.data.downloads.MonthlyDownloadsFactory
import dev.ja.marketplace.data.funnel.FunnelTableFactory
import dev.ja.marketplace.data.licenses.FallbackVersion
import dev.ja.marketplace.data.licenses.LicenseTable
import dev.ja.marketplace.data.licenses.LicenseStatus
import dev.ja.marketplace.data.licenses.LicenseTableFactory
import dev.ja.marketplace.data.lineItems.LineItemLicensesTable
import dev.ja.marketplace.data.lineItems.SaleLineItemSummary
import dev.ja.marketplace.data.offers.OfferUsageKey
import dev.ja.marketplace.data.offers.OfferUsageList
import dev.ja.marketplace.data.offers.OfferUsages
import dev.ja.marketplace.data.offers.OffersTableFactory
import dev.ja.marketplace.data.offers.OffersTableKind
import dev.ja.marketplace.data.overview.OverviewTableFactory
import dev.ja.marketplace.data.privingOverview.PricingOverviewTable
import dev.ja.marketplace.data.privingOverview.PricingOverviewTableFactory
import dev.ja.marketplace.data.resellers.ResellerTableFactory
import dev.ja.marketplace.data.subscriptions.AverageRevenuePerCustomerTableFactory
import dev.ja.marketplace.data.subscriptions.CohortRetentionTableFactory
import dev.ja.marketplace.data.subscriptions.MrrMovementsTableFactory
import dev.ja.marketplace.data.subscriptions.RealizedLifetimeValueTableFactory
import dev.ja.marketplace.data.subscriptions.RenewalRateTableFactory
import dev.ja.marketplace.data.subscriptions.RevenueRetentionTableFactory
import dev.ja.marketplace.data.releases.ReleasesTableFactories
import dev.ja.marketplace.data.timeSpanSummary.TimeSpanSummaryFactory
import dev.ja.marketplace.data.topCountries.TopCountriesFactory
import dev.ja.marketplace.data.topTrialCountries.TopTrialCountriesFactory
import dev.ja.marketplace.data.trials.TrialsTable
import dev.ja.marketplace.data.trials.TrialsTableFactory
import dev.ja.marketplace.data.yearSummary.AllPluginsMonthTable
import dev.ja.marketplace.data.yearSummary.AllPluginsSummaryTable
import dev.ja.marketplace.data.yearSummary.YearlySummaryFactory
import dev.ja.marketplace.exchangeRate.EcbExchangeRateData
import dev.ja.marketplace.exchangeRate.EcbRateTable
import dev.ja.marketplace.exchangeRate.ExchangeRates
import dev.ja.marketplace.mcp.MarketplaceMcpTools
import dev.ja.marketplace.mcp.McpDataProvider
import dev.ja.marketplace.mcp.marketplaceMcp
import dev.ja.marketplace.services.Countries
import dev.ja.marketplace.services.JetBrainsProductCode
import dev.ja.marketplace.services.ProductRelease
import dev.ja.marketplace.services.ProductReleaseType
import dev.ja.marketplace.services.Products
import dev.ja.marketplace.services.JetBrainsServiceClient
import gg.jte.ContentType
import gg.jte.TemplateEngine
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.http.content.*
import io.ktor.server.jte.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.compression.*
import io.ktor.server.plugins.conditionalheaders.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.encodeToStream
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

private val logger = LoggerFactory.getLogger(MarketplaceStatsServer::class.java)

class MarketplaceStatsServer(
    private val client: MarketplaceClient,
    private val servicesClient: JetBrainsServiceClient,
    private val host: String = "0.0.0.0",
    private val port: Int = 8080,
    private val serverConfiguration: ServerConfiguration,
    /**
     * Hostnames accepted by the MCP endpoint at `/mcp`, the endpoint is disabled if `null`.
     */
    private val mcpAllowedHosts: List<String>? = null,
    /** Refreshes the data of the requested plugins in the background, `null` if it's disabled. */
    private val autoRefresh: PluginAutoRefresh? = null,
    /** The ECB exchange rates, which all [ExchangeRates] of the server share. */
    private val ecbRates: EcbExchangeRateData,
) {
    private lateinit var allPlugins: List<PluginInfoSummary>
    private lateinit var countries: Countries
    private lateinit var exchangeRates: ExchangeRates
    private val ideReleases = IdeReleaseProvider(servicesClient)

    private val chartData = ChartData { pluginId -> ChartDownloadsSource.of(client, pluginId) }

    /** Work in the background, e.g. loading the data of all plugins after the start. */
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val faviconIco: ByteArray by lazy {
        MarketplaceStatsServer::class.java.getResourceAsStream("/favicon/favicon.ico")!!.use { it.readBytes() }
    }

    private val indexPageData: PluginPageDefinition = DefaultPluginPageDefinition(
        client,
        listOf(
            YearlySummaryFactory(),
            TimeSpanSummaryFactory(7, "Past 7 days"),
            DaySummaryFactory(0, "Today"),
            DaySummaryFactory(-1, "Yesterday"),
            CustomerTypeFactory(),
            TopCountriesFactory(smallSpace = true, maxCountries = 5),
            TopTrialCountriesFactory(5, smallSpace = true, showEmptyCountry = false),
            OverviewTableFactory()
        ),
        // the page exists for free plugins, the menu of the other plugins links to the same page of a free plugin
        isForFreePlugins = true,
    )

    // free plugins have no sales, licenses and trials, the tables only have their downloads
    private val indexPageDataFree: PluginPageDefinition = DefaultPluginPageDefinition(
        client,
        listOf(
            YearlySummaryFactory(),
            OverviewTableFactory(),
        ),
        // the page exists for free plugins, the menu of the other plugins links to the same page of a free plugin
        isForFreePlugins = true,
    )

    private val licensePageData: PluginPageDefinition = DefaultPluginPageDefinition(
        client,
        listOf(LicenseTableFactory()),
        pageCssClasses = "wide",
        pageTitle = "Licenses",
    )

    private val activeLicensesPageData: PluginPageDefinition = DefaultPluginPageDefinition(
        client,
        listOf(LicenseTableFactory(LicenseStatus.Active)),
        pageCssClasses = "wide",
        pageTitle = "Active Licenses",
    )

    private val churnedLicensesPageData: PluginPageDefinition = DefaultPluginPageDefinition(
        client,
        listOf(LicenseTableFactory(LicenseStatus.Churned)),
        pageCssClasses = "wide",
        pageTitle = "Churned Licenses",
        pageDescription = "Licenses, which ended and weren't renewed by any customer. " +
                "Licenses renewed by another customer, e.g. after a transfer to an organization, are active.",
    )

    private val countriesPageData: PluginPageDefinition = DefaultPluginPageDefinition(
        client,
        listOf(TopCountriesFactory(smallSpace = false, maxCountries = null, showTrials = true, showChurn = true, collapsedRowCount = 20)),
        pageTitle = "Countries and Trials",
    )

    private val allCustomersPageData: PluginPageDefinition = DefaultPluginPageDefinition(
        client,
        listOf(CustomerTableFactory()),
        pageCssClasses = "wide",
        pageTitle = "Customers",
    )

    private val trialsPageData: PluginPageDefinition = DefaultPluginPageDefinition(
        client,
        listOf(TrialsTableFactory()),
        pageCssClasses = "wide",
        pageTitle = "Trials",
    )

    private val trialCountriesPageData: PluginPageDefinition = DefaultPluginPageDefinition(
        client,
        listOf(TopTrialCountriesFactory(Int.MAX_VALUE, smallSpace = false, showEmptyCountry = true)),
        pageTitle = "Trials Grouped by Country",
    )

    private val funnelPageData: PluginPageDefinition = DefaultPluginPageDefinition(
        client,
        listOf(FunnelTableFactory(collapsedRowCount = 20)),
        pageTitle = "Trial Funnel",
    )

    private val subscriptionsPageData: PluginPageDefinition = DefaultPluginPageDefinition(
        client,
        listOf(
            MrrMovementsTableFactory(),
            RevenueRetentionTableFactory(),
            CohortRetentionTableFactory(),
            RealizedLifetimeValueTableFactory(),
            RenewalRateTableFactory(LicensePeriod.Annual),
            RenewalRateTableFactory(LicensePeriod.Monthly),
            AverageRevenuePerCustomerTableFactory(),
        ),
        pageCssClasses = "wide",
        pageTitle = "Subscription Metrics",
        pageDescription = "Calculated from the monthly recurring revenue (MRR) of each customer at the end of each month.<br>" +
                "<strong>Experimental:</strong> these metrics are new and not yet thoroughly tested or reviewed. " +
                "Please verify important numbers before you rely on them.",
    )

    private val monthlyDownloadsPageData: PluginPageDefinition = DefaultPluginPageDefinition(
        client,
        listOf(MonthlyDownloadsFactory()),
        pageTitle = "Downloads",
        // the page exists for free plugins, the menu of the other plugins links to the same page of a free plugin
        isForFreePlugins = true,
    )

    private val resellerPageData: PluginPageDefinition = DefaultPluginPageDefinition(
        client,
        listOf(ResellerTableFactory()),
        pageTitle = "Resellers",
    )

    private val pricingPageData: PluginPageDefinition = DefaultPluginPageDefinition(
        client,
        listOf(PricingOverviewTableFactory()),
        pageTitle = "Plugin Pricing",
        pageDescription = "The pricing shown on the JetBrains Marketplace.<br>" +
                "If there are two prices in the same column, then the 1st excludes VAT and the 2nd includes VAT.",
        pageNote = { data -> PricingOverviewTable.continuityDiscountNote(data.getMarketplacePluginInfo()) },
        // the prices are loaded when the table is calculated, they aren't part of the version of the data
        cacheTables = false,
    )

    private val releasesPageData: PluginPageDefinition = DefaultPluginPageDefinition(
        client,
        emptyList(),
        pageTitle = "Releases",
        pageDescription = "The releases of each channel and their downloads.",
        // the releases are loaded when the tables are created, they aren't part of the version of the data
        cacheTables = false,
        pluginFactories = { data -> ReleasesTableFactories.create(client, data.pluginId) },
        // the page exists for free plugins, the menu of the other plugins links to the same page of a free plugin
        isForFreePlugins = true,
    )

    private val offersPageData: PluginPageDefinition = DefaultPluginPageDefinition(
        client,
        listOf(
            OffersTableFactory(OffersTableKind.Marketplace),
            OffersTableFactory(OffersTableKind.Offers),
        ),
        pageCssClasses = "wide",
        pageTitle = "Offers and Discounts",
        pageDescription = "How the offers and other discounts were used. Sales are matched to offers by name, " +
                "the discount amount is calculated from the percent of the discount of each sale.",
        hideEmptyTables = true,
        // the offers are loaded when the tables are calculated, they aren't part of the version of the data
        cacheTables = false,
    )

    private val chargesPageData: PluginPageDefinition = DefaultPluginPageDefinition(
        client,
        listOf(ChargeOverviewTableFactory()),
        pageTitle = "Incorrect Plugin Charges",
    )

    private val mcpDataProvider = object : McpDataProvider {
        override suspend fun userInfo(): UserInfo = client.userInfo()

        override fun plugins(): List<PluginInfoSummary> = allPlugins

        override suspend fun pluginData(plugin: PluginInfoSummary): PluginData = getDataLoader(plugin).load()

        override suspend fun pluginReleases(plugin: PluginInfoSummary, channel: PluginChannel): List<PluginReleaseInfo> {
            return client.releases(plugin.id, channel, pageSize = 100)
        }

        override suspend fun pluginChannels(plugin: PluginInfoSummary): List<PluginChannel> {
            return client.channels(plugin.id)
        }

        override suspend fun verificationResults(release: PluginReleaseId, type: PluginVerificationType): List<PluginVerificationResult> {
            return client.verificationResults(release, type)
        }

        override suspend fun offers(plugin: PluginInfoSummary): List<PluginOffer> = client.offers(plugin.id)

        override suspend fun offerCoupons(plugin: PluginInfoSummary, offer: PluginOfferId): List<PluginOfferCoupon> {
            return client.offerCoupons(plugin.id, offer)
        }

        override suspend fun monetization(plugin: PluginInfoSummary): PluginMonetization = client.monetization(plugin.id)

        // cached by the services client
        override suspend fun productReleases(product: JetBrainsProductCode, type: ProductReleaseType): List<ProductRelease> {
            return servicesClient.productReleases(product, type)
        }

        override suspend fun products(): Products = servicesClient.products()

        override fun dropCachedData(plugin: PluginInfoSummary?) {
            when (plugin) {
                null -> this@MarketplaceStatsServer.dropCachedData()
                else -> (client as? CaffeineCacheMarketplaceClient)?.invalidatePlugin(plugin.id)
            }
        }

        override fun exchangeRates(currencyCode: String?): ExchangeRates {
            return when (currencyCode) {
                null, exchangeRates.targetCurrency.currencyCode -> exchangeRates
                else -> mcpExchangeRates.computeIfAbsent(currencyCode) { ExchangeRates(it, ecbRates) }
            }
        }

        override suspend fun exchangeRateTable(): EcbRateTable {
            return withContext(Dispatchers.IO) { ecbRates.get() }
        }

        override suspend fun groupedDownloads(
            plugin: PluginInfoSummary,
            dimension: DownloadRequestDimension,
            groupType: DownloadRequestDimension,
            countType: DownloadCountType,
            startDate: YearMonthDay?,
            vararg filters: DownloadFilter,
        ): GroupedDownloadResponse {
            return client.groupedDownloads(plugin.id, dimension, groupType, countType, startDate, *filters)
        }

        override suspend fun reviews(plugin: PluginInfoSummary): List<PluginReviewComment> {
            return client.reviewComments(plugin.id, pageSize = 100)
        }

        override suspend fun pageAnalytics(plugin: PluginInfoSummary, timeFrame: PageAnalyticsTimeFrame): PluginPageAnalytics {
            return client.pageAnalytics(plugin.id, timeFrame)
        }

        // the same values as the charts of the web interface, see /chart-data
        override suspend fun chartValues(plugin: PluginInfoSummary?): ChartMetricValues {
            return when {
                plugin != null -> chartData.values(getDataLoader(plugin).load())
                serverConfiguration.showAllPluginsSummary -> allPluginsChartValues()
                else -> throw IllegalArgumentException("pluginId is required, the summary of all plugins is disabled")
            }
        }

        override fun customEvents(plugins: Collection<PluginId>): List<CustomEvent> = serverConfiguration.customEvents(plugins)
    }

    // exchange rates into other currencies than the displayed currency, requested by MCP tools
    private val mcpExchangeRates = ConcurrentHashMap<String, ExchangeRates>()



    private val httpServer = embeddedServer(Netty, host = host, port = port) {
        install(Compression) {
            gzip()
            deflate()
        }
        // the pages are written to the response while they're rendered, see StreamingJte
        install(StreamingJte) {
            templateEngine = TemplateEngine.createPrecompiled(ContentType.Html).also {
                it.prepareForRendering("main.kte")
                it.prepareForRendering("plugins.kte")
            }
        }
        install(StatusPages) {
            exception<Exception> { call, cause ->
                call.respond(
                    JteContent(
                        "error_exception.kte", mapOf(
                            "exception" to cause,
                        )
                    )
                )
            }
        }

        if (mcpAllowedHosts != null) {
            marketplaceMcp(MarketplaceMcpTools(mcpDataProvider), mcpAllowedHosts)
        }

        // answers requests of static files with 304, if the ETag of the browser is the hash of the content
        install(ConditionalHeaders)

        routing {
            staticAssets("/styles", "styles")
            staticAssets("/js", "js")
            staticAssets("/favicon", "favicon")
            staticAssets("/fonts", "fonts")
            // browsers request /favicon.ico, also for pages without a link to it
            get("/favicon.ico") {
                call.respondBytes(faviconIco, ContentType("image", "x-icon"))
            }

            post("/refresh") {
                // the page, which sent the request, e.g. a list of licenses, whose rows are shown, keeps its parameters
                val refererUrl = call.request.header("Referer")
                    .asNullableUrl()
                    ?.takeIf { isPageOfRequestedServer(it, call.request.host(), call.request.port()) }

                val pluginId = call.request.queryParameters["pluginId"]?.toIntOrNull()
                    ?: (refererUrl?.parameters?.get("pluginId") ?: refererUrl?.parameters?.get(PLUGIN_ID_SHORT_PARAMETER))?.toInt()

                val dropCachedData = call.request.queryParameters["reload"]?.toBooleanStrictOrNull() == true
                if (dropCachedData) {
                    dropCachedData()
                }

                // the filters of the lists of the licenses and customers of an offer, see LinkedOfferUsage
                val whitelistedParamsNames = setOf(
                    "rows", OfferUsageKey.OFFER_PARAMETER, OfferUsageKey.OTHER_OFFER_PARAMETER, OfferUsageKey.DISCOUNT_PARAMETER,
                )
                val whitelistedRequestParams = call.request.queryParameters.filter(keepEmpty = true) { name, value ->
                    name in whitelistedParamsNames && value.isNotBlank()
                }
                val whitelistedRefererParams = refererUrl?.parameters?.filter { name, value ->
                    name in whitelistedParamsNames && value.isNotBlank() && name !in whitelistedRequestParams
                } ?: Parameters.Empty

                call.respondRedirect {
                    path(refererUrl?.encodedPath ?: "/")

                    parameters.appendMissing(whitelistedRequestParams)
                    parameters.appendMissing(whitelistedRefererParams)
                    if (pluginId != null) {
                        parameters["pluginId"] = pluginId.toString()
                    }
                }
            }

            // the metrics of the charts, e.g. to add them to the charts, see ChartData.metrics
            get("/chart-metrics") {
                val values = chartMetricValues() ?: return@get
                call.respondJsonStream(ListSerializer(ChartMetricData.serializer()), ChartData.metrics(values))
            }

            // the values of a parameter of a family of metrics, e.g. /chart-metric-parameters?pluginId=1&family=release-downloads&parameter=release
            get("/chart-metric-parameters") {
                val familyId = call.request.queryParameters["family"] ?: ""
                val parameterId = call.request.queryParameters["parameter"] ?: ""
                if (ChartMetricFamily.byId(familyId)?.parameters?.any { it.id == parameterId } != true) {
                    call.respondText("Unknown family or parameter", status = HttpStatusCode.BadRequest)
                    return@get
                }
                // the releases may take long to load, e.g. of a plugin with many releases
                val result = call.cancelOnDisconnect {
                    val values = chartMetricValues() ?: return@cancelOnDisconnect null
                    ChartData.parameterValues(values, familyId, parameterId)
                } ?: return@get
                call.respondJsonStream(ListSerializer(ChartParameterValueData.serializer()), result)
            }

            // values of the metrics of the charts, e.g. /chart-data?pluginId=1&metrics=sales,mrr&window=month&detail=day
            get("/chart-data") {
                val parameters = call.request.queryParameters
                val window = ChartWindow.byId(parameters["window"] ?: ChartWindow.Month.id)
                val detail = ChartDetail.byId(parameters["detail"] ?: ChartDetail.Month.id)
                val metrics = parameters["metrics"]?.split(',')?.map(String::trim)?.filter(String::isNotEmpty) ?: emptyList()
                // the first shown date of a chart, e.g. the downloads of a release are only loaded from then
                val from = parameters["from"]?.let { runCatching { YearMonthDay.parse(it) }.getOrNull() ?: YearMonthDay.MIN }
                if (window == null || detail == null || metrics.size > ChartData.MAX_METRICS || from == YearMonthDay.MIN) {
                    call.respondText("Invalid window, detail, metrics or from", status = HttpStatusCode.BadRequest)
                    return@get
                }

                // closing the chart dialog aborts the request, e.g. the downloads of a release aren't loaded anymore
                val response = call.cancelOnDisconnect {
                    val values = chartMetricValues() ?: return@cancelOnDisconnect null
                    ChartData.prepare(values, metrics, from, window)
                    withContext(Dispatchers.Default) { ChartData.chartData(values, metrics, window, detail) }
                } ?: return@get
                call.respondJsonStream(ChartDataResponse.serializer(), response)
            }

            // the values of the lines of a chart as CSV or JSON, e.g. for AI agents, see ChartExport
            // e.g. /chart-export?pluginId=1&detail=month&lines=sales:calendar-month,sales@avg3:calendar-month,mrr
            get("/chart-export") {
                val request = try {
                    ChartExport.parse(call.request.queryParameters)
                } catch (e: ChartExport.InvalidRequestException) {
                    call.respondText(e.message ?: "Invalid request", status = HttpStatusCode.BadRequest)
                    return@get
                }

                val table = call.cancelOnDisconnect {
                    val (plugin, values) = chartSource() ?: return@cancelOnDisconnect null
                    // the previous year needs the year before the dates
                    val months = request.lines.maxOfOrNull { (it.averageMonths ?: 0) + if (it.previousYear) 12 else 0 } ?: 0
                    values.prepare(request.lines.map { it.metric }, ChartData.loadStart(request.from, request.lines.map { it.window }, months))
                    withContext(Dispatchers.Default) { ChartExport.table(values, request, plugin?.let { it.id to it.name }) }
                } ?: return@get
                // the values are calculated with the current data
                call.response.header(HttpHeaders.CacheControl, "no-store")
                when (request.format) {
                    ChartExport.Format.Csv -> call.respondText(ChartExport.csv(table), request.format.contentType)
                    ChartExport.Format.Json -> call.respondJsonStream(ChartExportJson.serializer(), ChartExport.json(table))
                }
            }

            get("/") {
                val loader = getDataLoader()
                    ?: allPlugins.singleOrNull()?.let(::getDataLoader)

                if (loader != null) {
                    val pageData = when {
                        loader.plugin.isPaidOrFreemium -> indexPageData
                        else -> indexPageDataFree
                    }
                    call.respond(JteContent("main.kte", pageData.createTemplateParameters(loader, call.request, serverConfiguration)))
                } else {
                    call.respond(
                        JteContent(
                            "plugins.kte",
                            mapOf(
                                "plugins" to allPlugins,
                                "urls" to client as MarketplaceUrlSupport,
                                "summaryTables" to allPluginsSummary(),
                                "ideReleases" to if (serverConfiguration.showAllPluginsSummary) ideReleases.majorReleases() else emptyList(),
                                // the summary only contains the paid plugins, the events of the free plugins don't apply to it
                                "customEvents" to if (serverConfiguration.showAllPluginsSummary) {
                                    serverConfiguration.customEvents(allPlugins.filter(PluginInfoSummary::isPaidOrFreemium).map(PluginInfoSummary::id).toSet())
                                } else {
                                    emptyList()
                                },
                            )
                        )
                    )
                }
            }
            get("/licenses") {
                val loader = getDataLoader()
                    ?: throw IllegalStateException("Unable to find plugin")
                // the licenses with an offer or a discount, see LinkedOfferUsage
                val offerUsage = OfferUsageKey.fromParameters(call.request.queryParameters::get)
                if (offerUsage != null) {
                    renderOfferUsagePage(loader, OfferUsageList.Licenses, offerUsage, serverConfiguration)
                    return@get
                }
                call.respond(JteContent("main.kte", licensePageData.createTemplateParameters(loader, call.request, serverConfiguration)))
            }
            get("/licenses/active") {
                val loader = getDataLoader()
                    ?: throw IllegalStateException("Unable to find plugin")
                call.respond(JteContent("main.kte", activeLicensesPageData.createTemplateParameters(loader, call.request, serverConfiguration)))
            }
            get("/licenses/churned") {
                val loader = getDataLoader()
                    ?: throw IllegalStateException("Unable to find plugin")
                call.respond(JteContent("main.kte", churnedLicensesPageData.createTemplateParameters(loader, call.request, serverConfiguration)))
            }
            get("/countries") {
                val loader = getDataLoader()
                    ?: throw IllegalStateException("Unable to find plugin")
                call.respond(
                    JteContent(
                        "main.kte",
                        countriesPageData.createTemplateParameters(loader, call.request, serverConfiguration)
                    )
                )
            }
            get("/customers") {
                val loader = getDataLoader()
                    ?: throw IllegalStateException("Unable to find plugin")
                // the customers with an offer or a discount, see LinkedOfferUsage
                val offerUsage = OfferUsageKey.fromParameters(call.request.queryParameters::get)
                if (offerUsage != null) {
                    renderOfferUsagePage(loader, OfferUsageList.Customers, offerUsage, serverConfiguration)
                    return@get
                }
                call.respond(
                    JteContent(
                        "main.kte", allCustomersPageData.createTemplateParameters(
                            loader,
                            call.request,
                            serverConfiguration
                        )
                    )
                )
            }
            // the customers are on a single page, customers aren't churned, only licenses
            get("/customers/active") {
                call.respondRedirect("/customers?${call.request.queryString()}")
            }
            get("/customers/churned") {
                call.respondRedirect("/licenses/churned?${call.request.queryString()}")
            }
            get("/trials") {
                val loader = getDataLoader()
                    ?: throw IllegalStateException("Unable to find plugin")
                call.respond(JteContent("main.kte", trialsPageData.createTemplateParameters(loader, call.request, serverConfiguration)))
            }
            get("/trials/countries") {
                val loader = getDataLoader()
                    ?: throw IllegalStateException("Unable to find plugin")
                call.respond(
                    JteContent(
                        "main.kte", trialCountriesPageData.createTemplateParameters(
                            loader,
                            call.request,
                            serverConfiguration
                        )
                    )
                )
            }
            get("/trials/funnel") {
                val loader = getDataLoader()
                    ?: throw IllegalStateException("Unable to find plugin")
                call.respond(JteContent("main.kte", funnelPageData.createTemplateParameters(loader, call.request, serverConfiguration)))
            }
            get("/subscriptions") {
                val loader = getDataLoader()
                    ?: throw IllegalStateException("Unable to find plugin")
                call.respond(JteContent("main.kte", subscriptionsPageData.createTemplateParameters(loader, call.request, serverConfiguration)))
            }
            get("/downloads/monthly") {
                val loader = getDataLoader()
                    ?: throw IllegalStateException("Unable to find plugin")
                call.respond(
                    JteContent(
                        "main.kte", monthlyDownloadsPageData.createTemplateParameters(
                            loader,
                            call.request,
                            serverConfiguration
                        )
                    )
                )
            }
            get("/customer/{id}") {
                val loader = getDataLoader()
                    ?: throw IllegalStateException("Unable to find plugin")
                val customerId: CustomerId = call.parameters["id"]?.toIntOrNull()
                    ?: throw IllegalStateException("unable to find customer id")

                renderCustomerPage(loader, customerId, serverConfiguration)
            }
            get("/license/{id}") {
                val loader = getDataLoader()
                    ?: throw IllegalStateException("Unable to find plugin")
                val licenseId: LicenseId = call.parameters["id"]
                    ?: throw IllegalStateException("unable to find customer id")

                renderLicensePage(loader, licenseId, serverConfiguration)
            }
            get("/releases") {
                val loader = getDataLoader()
                    ?: throw IllegalStateException("Unable to find plugin")
                call.respond(JteContent("main.kte", releasesPageData.createTemplateParameters(loader, call.request, serverConfiguration)))
            }
            get("/release/{id}") {
                val loader = getDataLoader()
                    ?: throw IllegalStateException("Unable to find plugin")
                val releaseId = call.parameters["id"]?.toIntOrNull()
                if (releaseId == null) {
                    call.respondText("Invalid release ID", status = HttpStatusCode.BadRequest)
                    return@get
                }
                renderReleasePage(loader, releaseId, serverConfiguration)
            }
            get("/refnum/{id}") {
                val loader = getDataLoader()
                    ?: throw IllegalStateException("Unable to find plugin")
                val refnum: String = call.parameters["id"]
                    ?: throw IllegalStateException("unable to find refnum")

                renderRefNumPage(loader, refnum, serverConfiguration)
            }
            get("/churn-rate/{licensePeriod}/{lastActiveMarker}/{activeMarker}") {
                val loader = getDataLoader()
                    ?: throw IllegalStateException("Unable to find plugin")
                // null for the combined churn of all periods
                val period = when (val periodName = call.parameters["licensePeriod"]) {
                    "annual" -> LicensePeriod.Annual
                    "monthly" -> LicensePeriod.Monthly
                    LinkedChurnRate.ALL_PERIODS_LINK_SEGMENT -> null
                    else -> throw IllegalStateException("Unknown license period: $periodName")
                }
                val lastActiveMarker = YearMonthDay.parse(call.parameters["lastActiveMarker"]!!)
                val activeMarker = YearMonthDay.parse(call.parameters["activeMarker"]!!)

                renderLicenseChurnRatePage(loader, period, lastActiveMarker, activeMarker, serverConfiguration)
            }
            get("/resellers") {
                val loader = getDataLoader()
                    ?: throw IllegalStateException("Unable to find plugin")
                call.respond(
                    JteContent(
                        "main.kte",
                        resellerPageData.createTemplateParameters(loader, call.request, serverConfiguration)
                    )
                )
            }
            get("/pricing") {
                val loader = getDataLoader()
                    ?: throw IllegalStateException("Unable to find plugin")
                call.respond(JteContent("main.kte", pricingPageData.createTemplateParameters(loader, call.request, serverConfiguration)))
            }
            get("/offers") {
                val loader = getDataLoader()
                    ?: throw IllegalStateException("Unable to find plugin")
                call.respond(JteContent("main.kte", offersPageData.createTemplateParameters(loader, call.request, serverConfiguration)))
            }
            get("/charges") {
                val loader = getDataLoader()
                    ?: throw IllegalStateException("Unable to find plugin")
                call.respond(JteContent("main.kte", chargesPageData.createTemplateParameters(loader, call.request, serverConfiguration)))
            }
        }
    }

    suspend fun start() = coroutineScope {
        val countriesAsync = async(Dispatchers.IO) { servicesClient.countries() }
        val allPluginsAsync = async(Dispatchers.IO) { client.allPlugins(client.userInfo().id).sortedBy(PluginInfoSummary::name) }

        countries = countriesAsync.await()
        allPlugins = allPluginsAsync.await()
        exchangeRates = ExchangeRates(serverConfiguration.userDisplayCurrencyCode, ecbRates)
        // stored data is updated after the start, also without a report being opened
        (client as? CaffeineCacheMarketplaceClient)?.updateStoredHistory(allPlugins.filter(PluginInfoSummary::isPaidOrFreemium).map(PluginInfoSummary::id))
        if (serverConfiguration.showAllPluginsSummary) {
            // the summary of all paid plugins needs their data, it's loaded in the background, so that the list is fast.
            // Its chunk requests have the lower limit of background requests, so that they don't delay pages.
            backgroundScope.launch(BackgroundRequests) {
                try {
                    loadAllPluginsData()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.warn("Unable to load the data of all plugins", e)
                }
            }
        }

        autoRefresh?.start(backgroundScope)

        println("Launching web server: http://$host:$port/")
        if (mcpAllowedHosts != null) {
            println("MCP server: http://$host:$port/mcp")
        }
        httpServer.start(true)
    }

    private fun dropCachedData() {
        exchangeRates.invalidateCache()
        mcpExchangeRates.values.forEach(ExchangeRates::invalidateCache)
        if (client is CacheAware) {
            client.invalidateCache()
        }
        (servicesClient as? CacheAware)?.invalidateCache()
    }

    private class AllPluginsData(
        val sales: List<PluginSale>,
        val licenses: List<LicenseInfo>,
        val monthlyDownloads: List<MonthlyDownload>,
        val dailyDownloads: List<DailyDownload>,
    ) {
        /** The client returns the same lists, until it loads the data again. */
        fun isSameData(other: AllPluginsData): Boolean {
            return sales === other.sales && licenses === other.licenses
                    && monthlyDownloads === other.monthlyDownloads && dailyDownloads === other.dailyDownloads
        }
    }

    /**
     * A value calculated from the data of all plugins, which is reused while the data and the day don't change.
     */
    private class AllPluginsCache<T>(val data: List<AllPluginsData>, val today: YearMonthDay, val value: T) {
        fun isValid(data: List<AllPluginsData>, today: YearMonthDay): Boolean {
            return today == this.today && data.size == this.data.size && data.zip(this.data).all { (a, b) -> a.isSameData(b) }
        }
    }

    @Volatile
    private var allPluginsSummaryCache: AllPluginsCache<List<RenderedDataTable>>? = null

    @Volatile
    private var allPluginsChartValuesCache: AllPluginsCache<ChartMetricValues>? = null

    /** Concurrent requests of the charts of all plugins share their values. */
    private val allPluginsChartValuesLock = Mutex()

    /**
     * The sales and downloads of all paid and freemium plugins for their summary. Free plugins are left out.
     * The client caches the data of the plugins, also in the persistent cache. Plugins, whose data can't be loaded,
     * are left out.
     */
    private suspend fun loadAllPluginsData(): List<AllPluginsData> {
        // the plugins are loaded concurrently, the client limits the concurrent requests and the requests per second
        return coroutineScope {
            allPlugins.filter(PluginInfoSummary::isPaidOrFreemium).map { plugin ->
                async(Dispatchers.IO) {
                    try {
                        val salesAndLicenses = client.licenseInfo(plugin.id)
                        AllPluginsData(
                            salesAndLicenses.sales,
                            salesAndLicenses.licenses,
                            client.downloadsMonthly(plugin.id, DownloadCountType.Downloads),
                            client.downloadsDaily(plugin.id, DownloadCountType.Downloads),
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // the summary is shown without the plugin, the list of plugins is more important
                        logger.warn("Unable to load the data of plugin ${plugin.id} for the summary of all plugins", e)
                        null
                    }
                }
            }.awaitAll().filterNotNull()
        }
    }

    /**
     * The sales and downloads of all paid and freemium plugins by year and the sales of the current month,
     * shown above the list of plugins.
     *
     * @return The tables, empty if the summary is disabled or there are no paid plugins.
     */
    private suspend fun allPluginsSummary(): List<RenderedDataTable> {
        if (!serverConfiguration.showAllPluginsSummary || allPlugins.none(PluginInfoSummary::isPaidOrFreemium)) {
            return emptyList()
        }

        onAllPluginsRequest()
        val allData = loadAllPluginsData()
        val today = YearMonthDay.now()
        allPluginsSummaryCache?.takeIf { it.isValid(allData, today) }?.let { return it.value }

        val years = AllPluginsSummaryTable(exchangeRates)
        val month = AllPluginsMonthTable(exchangeRates, today)
        for (data in allData) {
            years.add(data.sales, data.licenses, data.monthlyDownloads, data.dailyDownloads)
            month.add(data.sales, data.licenses, data.dailyDownloads)
        }
        val tables = listOf(years.renderTable(), month.renderTable()).filterNot(RenderedDataTable::isEmpty)
        allPluginsSummaryCache = AllPluginsCache(allData, today, tables)
        return tables
    }

    /**
     * The summary of all plugins shows the data of all paid and freemium plugins, they're refreshed like requested plugins.
     */
    private fun onAllPluginsRequest() {
        allPlugins.filter(PluginInfoSummary::isPaidOrFreemium).forEach { autoRefresh?.onRequest(it.id) }
    }

    /**
     * The values of the summary of all plugins for each day, for the charts of the list of plugins.
     */
    private suspend fun allPluginsChartValues(): ChartMetricValues {
        onAllPluginsRequest()
        val allData = loadAllPluginsData()
        val today = YearMonthDay.now()
        return allPluginsChartValuesLock.withLock {
            allPluginsChartValuesCache?.takeIf { it.isValid(allData, today) }?.value ?: withContext(Dispatchers.Default) {
                ChartMetricValues.allPlugins(
                    allData.flatMap { it.sales },
                    allData.flatMap { it.dailyDownloads },
                    allData.map { it.licenses },
                    exchangeRates,
                    today,
                )
            }.also { allPluginsChartValuesCache = AllPluginsCache(allData, today, it) }
        }
    }

    /**
     * Writes the [value] as JSON into the response, without creating the text of the whole response in memory.
     */
    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun <T> ApplicationCall.respondJsonStream(serializer: SerializationStrategy<T>, value: T) {
        respondOutputStream(io.ktor.http.ContentType.Application.Json) {
            ChartData.json.encodeToStream(serializer, value, this)
        }
    }

    /**
     * @return The values of the charts of the plugin of the request, or of all plugins on the list of plugins, or `null`
     *  if the plugin is unknown. Errors are answered as text, the charts read JSON.
     */
    private suspend fun RoutingContext.chartMetricValues(): ChartMetricValues? = chartSource()?.second

    /**
     * @return The plugin of the request, `null` for the summary of all plugins, and the values of its charts, or `null`
     *  if the plugin is unknown, see [chartMetricValues].
     */
    private suspend fun RoutingContext.chartSource(): Pair<PluginInfoSummary?, ChartMetricValues>? {
        val pluginId = call.request.queryParameters["pluginId"]
        val plugin = when (pluginId) {
            null -> allPlugins.singleOrNull()
            else -> allPlugins.firstOrNull { it.id == pluginId.toIntOrNull() }
        }
        return when {
            plugin != null -> plugin to chartData.values(getDataLoader(plugin).load())
            // the summary of all plugins on the list of plugins
            pluginId == null && serverConfiguration.showAllPluginsSummary -> null to allPluginsChartValues()
            else -> {
                call.respondText("Unknown plugin", status = HttpStatusCode.NotFound)
                null
            }
        }
    }

    private fun getDataLoader(plugin: PluginInfoSummary): PluginDataLoader {
        // the pages and the tools of the MCP server load the data of a plugin with a loader
        autoRefresh?.onRequest(plugin.id)
        val (otherPaidPlugins, otherFreePlugins) = allPlugins.filter { it.id != plugin.id }.partition(PluginInfoSummary::isPaidOrFreemium)
        return PluginDataLoader(client, plugin, countries, exchangeRates, ideReleases, otherPaidPlugins, otherFreePlugins)
    }

    private fun RoutingContext.getDataLoader(): PluginDataLoader? {
        // the links of the large tables have the short parameter pId, see render.kte
        val pluginId = (call.request.queryParameters["pluginId"] ?: call.request.queryParameters[PLUGIN_ID_SHORT_PARAMETER])?.toInt()
            ?: return null
        val pluginSummary = allPlugins.firstOrNull { it.id == pluginId }
            ?: throw IllegalStateException("Unable to locate plugin data loader for $pluginId")
        return getDataLoader(pluginSummary)
    }

    private suspend fun RoutingContext.renderCustomerPage(
        loader: PluginDataLoader,
        customerId: CustomerId,
        serverConfiguration: ServerConfiguration
    ) {
        val data = loader.load()

        val customerInfo = data.getSales()?.firstOrNull { it.customer.code == customerId }?.customer
            ?: data.getTrials()?.firstOrNull { it.customer.code == customerId }?.customer
            ?: throw IllegalStateException("Customer with id $customerId not found")

        val sales = data.getSales()?.filter { it.customer.code == customerId } ?: emptyList()
        // we're not filtering licenses by customer ID here because the same license can be assigned to different customer
        // with new sales, e.g., when moved to an organization
        val licenses = data.getLicenses() ?: emptyList()
        val trials = data.getTrials()?.filter { it.customer.code == customerId } ?: emptyList()

        // only for plugins with fallback licenses
        val fallbackReleases = loader.fallbackReleases()

        val licenseTableMonthly = LicenseTable(showDetails = false, showReseller = true, showFooter = true, fallbackReleases = fallbackReleases) {
            it.sale.licensePeriod == LicensePeriod.Monthly && it.sale.customer.code == customerId
        }

        val licenseTableAnnual = LicenseTable(showDetails = false, showReseller = true, showFooter = true, fallbackReleases = fallbackReleases) {
            it.sale.licensePeriod == LicensePeriod.Annual && it.sale.customer.code == customerId
        }

        val licenseTablePerpetual = LicenseTable(showDetails = false, showReseller = true, showFooter = true) {
            it.sale.licensePeriod == LicensePeriod.Perpetual && it.sale.customer.code == customerId
        }

        val trialsTable = TrialsTable(showDetails = false) { trial -> trial.customer.code == customerId }

        for (table in listOf(licenseTableMonthly, licenseTableAnnual, licenseTablePerpetual, trialsTable)) {
            table.init(data)
            for (sale in sales) {
                table.process(sale)
            }
            for (license in licenses) {
                table.process(license)
            }
        }

        call.respond(
            JteContent(
                "customer.kte", mapOf(
                    "cssClass" to null,
                    "plugin" to data.getPluginInfo(),
                    "urls" to client as MarketplaceUrlSupport,
                    "rating" to data.getPluginRating(),
                    "today" to YearMonthDay.now(),
                    "otherPlugins" to loader.otherPaidPlugins,
                    "otherFreePlugins" to loader.otherFreePlugins,
                    "customer" to customerInfo,
                    "licenseTableMonthly" to licenseTableMonthly.renderTable(),
                    "licenseTableAnnual" to licenseTableAnnual.renderTable(),
                    "licenseTablePerpetual" to licenseTablePerpetual.renderTable(),
                    "trials" to trials,
                    "trialTable" to trialsTable.renderTable(),
                    "settings" to serverConfiguration,
                )
            )
        )
    }

    private suspend fun RoutingContext.renderLicensePage(
        loader: PluginDataLoader,
        licenseId: LicenseId,
        serverConfiguration: ServerConfiguration
    ) {
        val data = loader.load()
        val sales = data.getSales() ?: emptyList()
        val licenses = data.getLicenses() ?: emptyList()

        val licenseTable = LicenseTable(showLicenseColumn = false, showFooter = true, showReseller = true) { it.id == licenseId }
        for (table in listOf(licenseTable)) {
            table.init(data)
            for (sale in sales) {
                table.process(sale)
            }
            for (license in licenses) {
                table.process(license)
            }
        }

        call.respond(
            JteContent(
                "license.kte", mapOf(
                    "cssClass" to null,
                    "plugin" to data.getPluginInfo(),
                    "urls" to client as MarketplaceUrlSupport,
                    "rating" to data.getPluginRating(),
                    "today" to YearMonthDay.now(),
                    "otherPlugins" to loader.otherPaidPlugins,
                    "otherFreePlugins" to loader.otherFreePlugins,
                    "licenseId" to licenseId,
                    "licenseTable" to licenseTable.renderTable(),
                    "fallbackVersion" to loader.fallbackReleases()?.let { FallbackVersion.of(licenseId, licenses, it) },
                    "settings" to serverConfiguration,
                )
            )
        )
    }

    /**
     * @return The release dates of the versions, which the Marketplace uses for fallback licenses,
     *  or `null` if the plugin has no fallback licenses or if they couldn't be loaded.
     */
    private suspend fun PluginDataLoader.fallbackReleases(): List<PluginReleaseDate>? {
        return try {
            when (client.marketplacePluginInfo(plugin.id).licensingType) {
                LicensingType.SubscriptionWithFallback -> client.releaseDates(plugin.id)
                else -> null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Unable to load the release dates of plugin ${plugin.id} for the fallback licenses", e)
            null
        }
    }

    private suspend fun RoutingContext.renderRefNumPage(
        loader: PluginDataLoader,
        refNum: String,
        serverConfiguration: ServerConfiguration
    ) {
        val data = loader.load()
        val sale = data.getSales()?.firstOrNull { it.ref == refNum }
        if (sale == null) {
            call.respondText("Sale $refNum not found", status = HttpStatusCode.NotFound)
            return
        }

        // the details of each line item and its licenses
        val licenses = data.getLicenses() ?: emptyList()
        val lineItems = sale.lineItems.map { lineItem ->
            val licenseTable = LineItemLicensesTable(lineItem)
            licenseTable.init(data)
            licenses.forEach { licenseTable.process(it) }
            SaleLineItemSummary(sale, lineItem) to licenseTable.renderTable()
        }

        call.respond(
            JteContent(
                "refnum.kte", mapOf(
                    "cssClass" to null,
                    "plugin" to data.getPluginInfo(),
                    "urls" to client as MarketplaceUrlSupport,
                    "rating" to data.getPluginRating(),
                    "today" to YearMonthDay.now(),
                    "otherPlugins" to loader.otherPaidPlugins,
                    "otherFreePlugins" to loader.otherFreePlugins,
                    "sale" to sale,
                    "lineItems" to lineItems,
                    "settings" to serverConfiguration,
                )
            )
        )
    }

    private suspend fun RoutingContext.renderReleasePage(
        loader: PluginDataLoader,
        releaseId: PluginReleaseId,
        serverConfiguration: ServerConfiguration,
    ) {
        val data = loader.load()
        val details = ReleaseDetails.load(client, servicesClient, data.pluginId, releaseId)
        if (details == null) {
            call.respondText("Release $releaseId not found", status = HttpStatusCode.NotFound)
            return
        }

        call.respond(
            JteContent(
                "release.kte", mapOf(
                    "plugin" to data.getPluginInfo(),
                    "urls" to client as MarketplaceUrlSupport,
                    "rating" to data.getPluginRating(),
                    "today" to YearMonthDay.now(),
                    "otherPlugins" to loader.otherPaidPlugins,
                    "otherFreePlugins" to loader.otherFreePlugins,
                    "details" to details,
                    "settings" to serverConfiguration,
                )
            )
        )
    }

    /** Renders the licenses or the customers, which used an offer or a discount, see [OfferUsages]. */
    private suspend fun RoutingContext.renderOfferUsagePage(
        loader: PluginDataLoader,
        list: OfferUsageList,
        key: OfferUsageKey,
        serverConfiguration: ServerConfiguration,
    ) {
        val data = loader.load()
        val offers = if (data.pluginSummary.isPaidOrFreemium) client.offers(data.pluginId) else emptyList()
        val usages = OfferUsages(offers, data.exchangeRates)
        data.getLicenses()?.forEach(usages::add)
        val usage = usages.usage(key)

        val name = when (key) {
            is OfferUsageKey.Offer -> offers.firstOrNull { it.id == key.id }?.name ?: "Offer ${key.id}"
            is OfferUsageKey.OtherOffer -> key.name
            is OfferUsageKey.OtherDiscount -> key.description
        }
        val factory = object : MarketplaceDataTableFactory {
            override fun createTable(client: MarketplaceClient, maxTableRows: Int?): DataTable = when (list) {
                OfferUsageList.Licenses -> LicenseTable(maxTableRows, showFooter = true, supportedChurnStyling = false) { usage?.contains(it) == true }
                OfferUsageList.Customers -> CustomerTable(maxTableRows = maxTableRows, customerFilter = { usage?.hasCustomer(it) == true })
            }
        }
        val pageData = DefaultPluginPageDefinition(
            client,
            listOf(factory),
            pageTitle = "${if (list == OfferUsageList.Licenses) "Licenses" else "Customers"}: $name",
            pageCssClasses = "wide",
            // the offers are loaded for each request
            cacheTables = false,
        )
        call.respond(JteContent("main.kte", pageData.createTemplateParameters(loader, call.request, serverConfiguration)))
    }

    private suspend fun RoutingContext.renderLicenseChurnRatePage(
        loader: PluginDataLoader,
        period: LicensePeriod?,
        lastActiveMarker: YearMonthDay,
        activeMarker: YearMonthDay,
        serverConfiguration: ServerConfiguration
    ) {
        val data = loader.load()

        val churnProcessor = LicenseChurnProcessor(lastActiveMarker, activeMarker)

        val licenses = data.getLicenses()!!
        val churnValidities = ChurnValidities(licenses)
        licenses.filter(LicenseInfo::isSubscriptionLicense).forEach {
            churnProcessor.processValue(
                it,
                churnValidities.validity(it)!!,
                it.isCountedByChurn(period),
            )
        }

        val churnedIds = churnProcessor.churnedIds()

        val pageData = DefaultPluginPageDefinition(
            client,
            listOf(object : MarketplaceDataTableFactory {
                override fun createTable(client: MarketplaceClient, maxTableRows: Int?): DataTable {
                    return LicenseTable(
                        showFooter = true,
                        showPurchaseColumn = false,
                        supportedChurnStyling = false,
                        showOnlyLatestLicenseInfo = true,
                    ) {
                        it.id in churnedIds
                    }
                }
            }),
            pageTitle = buildString {
                append("Churned Licenses: ")
                append("${lastActiveMarker.add(0, 0, 1).asIsoString} — ${activeMarker.asIsoString}, ${period ?: "all"} subscriptions")
            },
            pageCssClasses = "wide"
        )

        call.respond(JteContent("main.kte", pageData.createTemplateParameters(loader, call.request, serverConfiguration)))
    }
}

/**
 * @return If the [referer] is a page of this server: its host and port are the ones of the request, e.g. `localhost:8081`,
 *  not the address the server is bound to, e.g. `0.0.0.0`.
 */
internal fun isPageOfRequestedServer(referer: Url, requestHost: String, requestPort: Int): Boolean {
    return referer.host.equals(requestHost, ignoreCase = true) && referer.port == requestPort
}

/**
 * The short name of the parameter `pluginId` of the links in the cells of the tables, e.g. of the 100,000 links of a
 * list of licenses, see render.kte. The pages accept both names.
 */
const val PLUGIN_ID_SHORT_PARAMETER = "pId"
