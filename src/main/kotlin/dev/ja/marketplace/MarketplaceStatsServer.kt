/*
 * Copyright (c) 2023-2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace

import dev.ja.marketplace.churn.MarketplaceChurnProcessor
import dev.ja.marketplace.client.*
import dev.ja.marketplace.data.LicenseInfo
import dev.ja.marketplace.data.MarketplaceDataSink
import dev.ja.marketplace.data.MarketplaceDataSinkFactory
import dev.ja.marketplace.data.customerType.CustomerTypeFactory
import dev.ja.marketplace.data.customers.ActiveCustomerTableFactory
import dev.ja.marketplace.data.customers.ChurnedCustomerTableFactory
import dev.ja.marketplace.data.customers.CustomerTableFactory
import dev.ja.marketplace.data.downloads.MonthlyDownloadsFactory
import dev.ja.marketplace.data.funnel.FunnelTableFactory
import dev.ja.marketplace.data.licenses.LicenseTable
import dev.ja.marketplace.data.licenses.LicenseTableFactory
import dev.ja.marketplace.data.overview.OverviewTableFactory
import dev.ja.marketplace.data.resellers.ResellerTableFactory
import dev.ja.marketplace.data.salesDay.SalesDayFactory
import dev.ja.marketplace.data.topCountries.TopCountriesFactory
import dev.ja.marketplace.data.topTrialCountries.TopTrialCountriesFactory
import dev.ja.marketplace.data.trials.TrialsTable
import dev.ja.marketplace.data.trials.TrialsTableFactory
import dev.ja.marketplace.data.trialsToday.TrialsTodayFactory
import dev.ja.marketplace.data.week.WeekFactory
import dev.ja.marketplace.data.yearSummary.YearlySummaryFactory
import gg.jte.ContentType
import gg.jte.TemplateEngine
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.http.content.*
import io.ktor.server.jte.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.compression.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.*
import io.ktor.util.pipeline.*

class MarketplaceStatsServer(
    private val client: MarketplaceClient,
    private val host: String = "0.0.0.0",
    private val port: Int = 8080
) {
    private lateinit var allPlugins: List<PluginInfoSummary>

    private fun getDataLoader(plugin: PluginInfoSummary): PluginDataLoader {
        return PluginDataLoader(plugin, client)
    }

    private fun PipelineContext<Unit, ApplicationCall>.getDataLoader(): PluginDataLoader? {
        val pluginId = context.request.queryParameters["pluginId"]?.toInt()
            ?: return null
        val pluginSummary = allPlugins.firstOrNull { it.id == pluginId }
            ?: throw IllegalStateException("Unable to locate plugin data loader for $pluginId")
        return getDataLoader(pluginSummary)
    }

    private val indexPageData: PluginPageDefinition = DefaultPluginPageDefinition(
        client,
        listOf(
            YearlySummaryFactory(),
            WeekFactory(),
            SalesDayFactory(YearMonthDay.now(), "Today"),
            SalesDayFactory(YearMonthDay.now().add(0, 0, -1), "Yesterday"),
            TrialsTodayFactory(),
            CustomerTypeFactory(),
            TopCountriesFactory(smallSpace = true),
            TopTrialCountriesFactory(smallSpace = true),
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
        listOf(TopTrialCountriesFactory(Int.MAX_VALUE, smallSpace = false)),
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
                if (dropCachedData && client is CachingMarketplaceClient) {
                    client.reset()
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
                    ?: allPlugins.singleOrNull()?.let { getDataLoader(it) }

                if (loader != null) {
                    val pageData = when {
                        loader.plugin.isPaidOrFreemium -> indexPageData
                        else -> indexPageDataFree
                    }
                    call.respond(JteContent("main.kte", pageData.createTemplateParameters(loader, context.request)))
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
                call.respond(JteContent("main.kte", licensePageData.createTemplateParameters(loader, context.request)))
            }
            get("/countries") {
                val loader = getDataLoader()
                    ?: throw IllegalStateException("Unable to find plugin")
                call.respond(JteContent("main.kte", countriesPageData.createTemplateParameters(loader, context.request)))
            }
            get("/customers") {
                val loader = getDataLoader()
                    ?: throw IllegalStateException("Unable to find plugin")
                call.respond(JteContent("main.kte", allCustomersPageData.createTemplateParameters(loader, context.request)))
            }
            get("/customers/active") {
                val loader = getDataLoader()
                    ?: throw IllegalStateException("Unable to find plugin")
                call.respond(JteContent("main.kte", activeCustomersPageData.createTemplateParameters(loader, context.request)))
            }
            get("/customers/churned") {
                val loader = getDataLoader()
                    ?: throw IllegalStateException("Unable to find plugin")
                call.respond(JteContent("main.kte", churnedCustomersPageData.createTemplateParameters(loader, context.request)))
            }
            get("/trials") {
                val loader = getDataLoader()
                    ?: throw IllegalStateException("Unable to find plugin")
                call.respond(JteContent("main.kte", trialsPageData.createTemplateParameters(loader, context.request)))
            }
            get("/trials/countries") {
                val loader = getDataLoader()
                    ?: throw IllegalStateException("Unable to find plugin")
                call.respond(JteContent("main.kte", trialCountriesPageData.createTemplateParameters(loader, context.request)))
            }
            get("/trials/funnel") {
                val loader = getDataLoader()
                    ?: throw IllegalStateException("Unable to find plugin")
                call.respond(JteContent("main.kte", funnelPageData.createTemplateParameters(loader, context.request)))
            }
            get("/downloads/monthly") {
                val loader = getDataLoader()
                    ?: throw IllegalStateException("Unable to find plugin")
                call.respond(JteContent("main.kte", monthlyDownloadsPageData.createTemplateParameters(loader, context.request)))
            }
            get("/customer/{id}") {
                val loader = getDataLoader()
                    ?: throw IllegalStateException("Unable to find plugin")
                val customerId: CustomerId = call.parameters["id"]?.toIntOrNull()
                    ?: throw IllegalStateException("unable to find customer id")

                renderCustomerPage(loader, customerId)
            }
            get("/license/{id}") {
                val loader = getDataLoader()
                    ?: throw IllegalStateException("Unable to find plugin")
                val licenseId: LicenseId = call.parameters["id"]
                    ?: throw IllegalStateException("unable to find customer id")

                renderLicensePage(loader, licenseId)
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

                renderLicenseChurnRatePage(loader, period, lastActiveMarker, activeMarker)
            }
            get("/resellers") {
                val loader = getDataLoader()
                    ?: throw IllegalStateException("Unable to find plugin")
                call.respond(JteContent("main.kte", resellerPageData.createTemplateParameters(loader, context.request)))
            }
        }
    }

    suspend fun start() {
        val userInfo = client.userInfo()
        this.allPlugins = client.plugins(userInfo.id).sortedBy { it.name }

        println("Launching web server: http://$host:$port/")
        httpServer.start(true)
    }

    private suspend fun PipelineContext<Unit, ApplicationCall>.renderCustomerPage(loader: PluginDataLoader, customerId: CustomerId) {
        val data = loader.load()

        val customerInfo = data.sales?.firstOrNull { it.customer.code == customerId }?.customer
            ?: data.trials?.firstOrNull { it.customer.code == customerId }?.customer
            ?: throw IllegalStateException("Customer with id $customerId not found")

        val sales = data.sales?.filter { it.customer.code == customerId } ?: emptyList()
        // we're not filtering licenses by customer ID here because the same license can be assigned to different customer
        // with new sales, e.g., when moved to an organization
        val licenses = data.licenses ?: emptyList()
        val trials = data.trials?.filter { it.customer.code == customerId } ?: emptyList()

        val licenseTableMonthly = LicenseTable(showDetails = false, showReseller = true, showFees = true, showFooter = true) {
            it.sale.licensePeriod == LicensePeriod.Monthly && it.sale.customer.code == customerId
        }

        val licenseTableAnnual = LicenseTable(showDetails = false, showReseller = true, showFees = true, showFooter = true) {
            it.sale.licensePeriod == LicensePeriod.Annual && it.sale.customer.code == customerId
        }

        val trialsTable = TrialsTable(showDetails = false) { trial -> trial.customer.code == customerId }

        listOf(licenseTableMonthly, licenseTableAnnual, trialsTable).forEach { table ->
            table.init(data)
            sales.forEach(table::process)
            licenses.forEach(table::process)
        }

        call.respond(
            JteContent(
                "customer.kte", mapOf(
                    "cssClass" to null,
                    "plugin" to data.pluginInfo,
                    "customer" to customerInfo,
                    "licenseTableMonthly" to licenseTableMonthly,
                    "licenseTableAnnual" to licenseTableAnnual,
                    "trials" to trials,
                    "trialTable" to trialsTable,
                )
            )
        )
    }

    private suspend fun PipelineContext<Unit, ApplicationCall>.renderLicensePage(loader: PluginDataLoader, licenseId: LicenseId) {
        val data = loader.load()
        val sales = data.sales ?: emptyList()
        val licenses = data.licenses ?: emptyList()

        val licenseTable = LicenseTable(showLicenseColumn = false, showFooter = true, showReseller = true) { it.id == licenseId }
        listOf(licenseTable).forEach { table ->
            table.init(data)
            sales.forEach(table::process)
            licenses.forEach(table::process)
        }

        call.respond(
            JteContent(
                "license.kte", mapOf(
                    "cssClass" to null,
                    "plugin" to data.pluginInfo,
                    "licenseId" to licenseId,
                    "licenseTable" to licenseTable,
                )
            )
        )
    }

    private suspend fun PipelineContext<Unit, ApplicationCall>.renderLicenseChurnRatePage(
        loader: PluginDataLoader,
        period: LicensePeriod,
        lastActiveMarker: YearMonthDay,
        activeMarker: YearMonthDay
    ) {
        val data = loader.load()

        val churnProcessor = MarketplaceChurnProcessor<LicenseId, LicenseInfo>(lastActiveMarker, activeMarker, ::HashSet)
        churnProcessor.init()

        data.licenses!!.forEach {
            churnProcessor.processValue(
                it.id,
                it,
                it.validity,
                it.sale.licensePeriod == period && it.isPaidLicense,
                it.isRenewal
            )
        }

        val churnedIds = churnProcessor.churnedIds()

        val pageData = DefaultPluginPageDefinition(
            client,
            listOf(object : MarketplaceDataSinkFactory {
                override fun createTableSink(client: MarketplaceClient, maxTableRows: Int?): MarketplaceDataSink {
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

        call.respond(JteContent("main.kte", pageData.createTemplateParameters(loader, context.request)))
    }
}