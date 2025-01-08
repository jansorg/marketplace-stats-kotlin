/*
 * Copyright (c) 2023-2025 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace

import dev.ja.marketplace.churn.LicenseChurnProcessor
import dev.ja.marketplace.client.*
import dev.ja.marketplace.client.model.LicensePeriod
import dev.ja.marketplace.client.model.PluginInfoSummary
import dev.ja.marketplace.data.DataTable
import dev.ja.marketplace.data.MarketplaceDataTableFactory
import dev.ja.marketplace.data.chargeOverview.ChargeOverviewTableFactory
import dev.ja.marketplace.data.customerType.CustomerTypeFactory
import dev.ja.marketplace.data.customers.ActiveCustomerTableFactory
import dev.ja.marketplace.data.customers.ChurnedCustomerTableFactory
import dev.ja.marketplace.data.customers.CustomerTableFactory
import dev.ja.marketplace.data.daySummary.DaySummaryFactory
import dev.ja.marketplace.data.downloads.MonthlyDownloadsFactory
import dev.ja.marketplace.data.funnel.FunnelTableFactory
import dev.ja.marketplace.data.licenses.LicenseTable
import dev.ja.marketplace.data.licenses.LicenseTableFactory
import dev.ja.marketplace.data.lineItems.LineItemsTable
import dev.ja.marketplace.data.overview.OverviewTableFactory
import dev.ja.marketplace.data.privingOverview.PricingOverviewTableFactory
import dev.ja.marketplace.data.resellers.ResellerTableFactory
import dev.ja.marketplace.data.timeSpanSummary.TimeSpanSummaryFactory
import dev.ja.marketplace.data.topCountries.TopCountriesFactory
import dev.ja.marketplace.data.topTrialCountries.TopTrialCountriesFactory
import dev.ja.marketplace.data.trials.TrialsTable
import dev.ja.marketplace.data.trials.TrialsTableFactory
import dev.ja.marketplace.data.yearSummary.YearlySummaryFactory
import dev.ja.marketplace.exchangeRate.ExchangeRates
import dev.ja.marketplace.services.Countries
import dev.ja.marketplace.services.KtorJetBrainsServiceClient
import gg.jte.ContentType
import gg.jte.TemplateEngine
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.http.content.*
import io.ktor.server.jte.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.compression.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.*
import io.ktor.util.pipeline.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

class MarketplaceStatsServer(
    private val client: MarketplaceClient,
    private val servicesClient: KtorJetBrainsServiceClient,
    private val host: String = "0.0.0.0",
    private val port: Int = 8080,
    private val serverConfiguration: ServerConfiguration,
) {
    private lateinit var allPlugins: List<PluginInfoSummary>
    private lateinit var countries: Countries
    private lateinit var exchangeRates: ExchangeRates

    private val indexPageData: PluginPageDefinition = DefaultPluginPageDefinition(
        client,
        listOf(
            YearlySummaryFactory(),
            TimeSpanSummaryFactory(7, "Past 7 days"),
            DaySummaryFactory(0, "Today"),
            DaySummaryFactory(-1, "Yesterday"),
            CustomerTypeFactory(),
            TopCountriesFactory(smallSpace = true),
            TopTrialCountriesFactory(10, smallSpace = true, showEmptyCountry = false),
            OverviewTableFactory()
        )
    )

    private val indexPageDataFree: PluginPageDefinition = DefaultPluginPageDefinition(
        client,
        listOf()
    )

    private val licensePageData: PluginPageDefinition = DefaultPluginPageDefinition(
        client,
        listOf(LicenseTableFactory()),
        pageCssClasses = "wide",
        pageTitle = "Licenses",
    )

    private val countriesPageData: PluginPageDefinition = DefaultPluginPageDefinition(
        client,
        listOf(TopCountriesFactory(smallSpace = false, maxCountries = null, showTrials = true)),
        pageTitle = "Countries and Trials",
    )

    private val allCustomersPageData: PluginPageDefinition = DefaultPluginPageDefinition(
        client,
        listOf(CustomerTableFactory()),
        pageCssClasses = "wide",
        pageTitle = "Customers (all)",
    )

    private val activeCustomersPageData: PluginPageDefinition = DefaultPluginPageDefinition(
        client,
        listOf(ActiveCustomerTableFactory()),
        pageCssClasses = "wide",
        pageTitle = "Customers With Active Licenses",
    )

    private val churnedCustomersPageData: PluginPageDefinition = DefaultPluginPageDefinition(
        client,
        listOf(ChurnedCustomerTableFactory()),
        pageCssClasses = "wide",
        pageTitle = "Churned Customers",
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
        listOf(FunnelTableFactory()),
        pageTitle = "Trial Funnel",
    )

    private val monthlyDownloadsPageData: PluginPageDefinition = DefaultPluginPageDefinition(
        client,
        listOf(MonthlyDownloadsFactory()),
        pageTitle = "Downloads",
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
                "If there are two prices in the same column, then the 1st excludes VAT and the 2nd includes VAT."
    )

    private val chargesPageData: PluginPageDefinition = DefaultPluginPageDefinition(
        client,
        listOf(ChargeOverviewTableFactory()),
        pageTitle = "Incorrect Plugin Charges",
    )

    private val httpServer = embeddedServer(Netty, host = host, port = port) {
        install(Compression) {
            gzip()
            deflate()
        }
        install(Jte) {
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

        routing {
            staticResources("/styles", "styles")
            staticResources("/js", "js")

            post("/refresh") {
                val refererUrl = context.request.header("Referer")
                    .asNullableUrl()
                    ?.takeIf { it.host == host && it.port == port }

                val pluginId = context.request.queryParameters["pluginId"]?.toIntOrNull()
                    ?: refererUrl?.parameters?.get("pluginId")?.toInt()

                val dropCachedData = context.request.queryParameters["reload"]?.toBooleanStrictOrNull() == true
                if (dropCachedData) {
                    exchangeRates.invalidateCache()
                    if (client is CacheAware) {
                        client.invalidateCache()
                    }
                }

                val whitelistedParamsNames = setOf("rows")
                val whitelistedRequestParams = context.request.queryParameters.filter(keepEmpty = true) { name, value ->
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

            get("/") {
                val loader = getDataLoader()
                    ?: allPlugins.singleOrNull()?.let(::getDataLoader)

                if (loader != null) {
                    val pageData = when {
                        loader.plugin.isPaidOrFreemium -> indexPageData
                        else -> indexPageDataFree
                    }
                    call.respond(JteContent("main.kte", pageData.createTemplateParameters(loader, context.request, serverConfiguration)))
                } else {
                    call.respond(
                        JteContent(
                            "plugins.kte",
                            mapOf("plugins" to allPlugins, "urls" to client as MarketplaceUrlSupport)
                        )
                    )
                }
            }
            get("/licenses") {
                val loader = getDataLoader()
                    ?: throw IllegalStateException("Unable to find plugin")
                call.respond(JteContent("main.kte", licensePageData.createTemplateParameters(loader, context.request, serverConfiguration)))
            }
            get("/countries") {
                val loader = getDataLoader()
                    ?: throw IllegalStateException("Unable to find plugin")
                call.respond(
                    JteContent(
                        "main.kte",
                        countriesPageData.createTemplateParameters(loader, context.request, serverConfiguration)
                    )
                )
            }
            get("/customers") {
                val loader = getDataLoader()
                    ?: throw IllegalStateException("Unable to find plugin")
                call.respond(
                    JteContent(
                        "main.kte", allCustomersPageData.createTemplateParameters(
                            loader,
                            context.request,
                            serverConfiguration
                        )
                    )
                )
            }
            get("/customers/active") {
                val loader = getDataLoader()
                    ?: throw IllegalStateException("Unable to find plugin")
                call.respond(
                    JteContent(
                        "main.kte", activeCustomersPageData.createTemplateParameters(
                            loader,
                            context.request,
                            serverConfiguration
                        )
                    )
                )
            }
            get("/customers/churned") {
                val loader = getDataLoader()
                    ?: throw IllegalStateException("Unable to find plugin")
                call.respond(
                    JteContent(
                        "main.kte", churnedCustomersPageData.createTemplateParameters(
                            loader,
                            context.request,
                            serverConfiguration
                        )
                    )
                )
            }
            get("/trials") {
                val loader = getDataLoader()
                    ?: throw IllegalStateException("Unable to find plugin")
                call.respond(JteContent("main.kte", trialsPageData.createTemplateParameters(loader, context.request, serverConfiguration)))
            }
            get("/trials/countries") {
                val loader = getDataLoader()
                    ?: throw IllegalStateException("Unable to find plugin")
                call.respond(
                    JteContent(
                        "main.kte", trialCountriesPageData.createTemplateParameters(
                            loader,
                            context.request,
                            serverConfiguration
                        )
                    )
                )
            }
            get("/trials/funnel") {
                val loader = getDataLoader()
                    ?: throw IllegalStateException("Unable to find plugin")
                call.respond(JteContent("main.kte", funnelPageData.createTemplateParameters(loader, context.request, serverConfiguration)))
            }
            get("/downloads/monthly") {
                val loader = getDataLoader()
                    ?: throw IllegalStateException("Unable to find plugin")
                call.respond(
                    JteContent(
                        "main.kte", monthlyDownloadsPageData.createTemplateParameters(
                            loader,
                            context.request,
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
                val period = when (val periodName = call.parameters["licensePeriod"]) {
                    "annual" -> LicensePeriod.Annual
                    "monthly" -> LicensePeriod.Monthly
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
                        resellerPageData.createTemplateParameters(loader, context.request, serverConfiguration)
                    )
                )
            }
            get("/pricing") {
                val loader = getDataLoader()
                    ?: throw IllegalStateException("Unable to find plugin")
                call.respond(JteContent("main.kte", pricingPageData.createTemplateParameters(loader, context.request, serverConfiguration)))
            }
            get("/charges") {
                val loader = getDataLoader()
                    ?: throw IllegalStateException("Unable to find plugin")
                call.respond(JteContent("main.kte", chargesPageData.createTemplateParameters(loader, context.request, serverConfiguration)))
            }
        }
    }

    suspend fun start() = coroutineScope {
        val countriesAsync = async(Dispatchers.IO) { servicesClient.countries() }
        val allPluginsAsync = async(Dispatchers.IO) { client.plugins(client.userInfo().id).sortedBy(PluginInfoSummary::name) }

        countries = countriesAsync.await()
        allPlugins = allPluginsAsync.await()
        exchangeRates = ExchangeRates(serverConfiguration.userDisplayCurrencyCode)

        println("Launching web server: http://$host:$port/")
        httpServer.start(true)
    }

    private fun getDataLoader(plugin: PluginInfoSummary): PluginDataLoader {
        return PluginDataLoader(client, plugin, countries, exchangeRates)
    }

    private fun PipelineContext<Unit, ApplicationCall>.getDataLoader(): PluginDataLoader? {
        val pluginId = context.request.queryParameters["pluginId"]?.toInt()
            ?: return null
        val pluginSummary = allPlugins.firstOrNull { it.id == pluginId }
            ?: throw IllegalStateException("Unable to locate plugin data loader for $pluginId")
        return getDataLoader(pluginSummary)
    }

    private suspend fun PipelineContext<Unit, ApplicationCall>.renderCustomerPage(
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

        val licenseTableMonthly = LicenseTable(showDetails = false, showReseller = true, showFooter = true) {
            it.sale.licensePeriod == LicensePeriod.Monthly && it.sale.customer.code == customerId
        }

        val licenseTableAnnual = LicenseTable(showDetails = false, showReseller = true, showFooter = true) {
            it.sale.licensePeriod == LicensePeriod.Annual && it.sale.customer.code == customerId
        }

        val trialsTable = TrialsTable(showDetails = false) { trial -> trial.customer.code == customerId }

        for (table in listOf(licenseTableMonthly, licenseTableAnnual, trialsTable)) {
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
                    "customer" to customerInfo,
                    "licenseTableMonthly" to licenseTableMonthly.renderTable(),
                    "licenseTableAnnual" to licenseTableAnnual.renderTable(),
                    "trials" to trials,
                    "trialTable" to trialsTable.renderTable(),
                    "settings" to serverConfiguration,
                )
            )
        )
    }

    private suspend fun PipelineContext<Unit, ApplicationCall>.renderLicensePage(
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
                    "licenseId" to licenseId,
                    "licenseTable" to licenseTable.renderTable(),
                    "settings" to serverConfiguration,
                )
            )
        )
    }

    private suspend fun PipelineContext<Unit, ApplicationCall>.renderRefNumPage(
        loader: PluginDataLoader,
        refNum: String,
        serverConfiguration: ServerConfiguration
    ) {
        val data = loader.load()
        val sale = data.getSales()?.single { it.ref == refNum }

        val lineItemsTable = LineItemsTable()
        for (table in listOf(lineItemsTable)) {
            table.init(data)
            table.process(sale!!)
        }

        call.respond(
            JteContent(
                "refnum.kte", mapOf(
                    "cssClass" to null,
                    "plugin" to data.getPluginInfo(),
                    "sale" to sale,
                    "lineItemsTable" to lineItemsTable.renderTable(),
                    "settings" to serverConfiguration,
                )
            )
        )
    }

    private suspend fun PipelineContext<Unit, ApplicationCall>.renderLicenseChurnRatePage(
        loader: PluginDataLoader,
        period: LicensePeriod,
        lastActiveMarker: YearMonthDay,
        activeMarker: YearMonthDay,
        serverConfiguration: ServerConfiguration
    ) {
        val data = loader.load()

        val churnProcessor = LicenseChurnProcessor(lastActiveMarker, activeMarker)
        churnProcessor.init()

        data.getLicenses()!!.forEach {
            churnProcessor.processValue(
                it,
                it.validity,
                it.sale.licensePeriod == period && it.isPaidLicense,
                it.isRenewalLicense
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
                append("${lastActiveMarker.add(0, 0, 1).asIsoString} — ${activeMarker.asIsoString}, $period subscriptions")
            },
            pageCssClasses = "wide"
        )

        call.respond(JteContent("main.kte", pageData.createTemplateParameters(loader, context.request, serverConfiguration)))
    }
}