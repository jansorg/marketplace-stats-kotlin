/*
 * Copyright (c) 2023 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace

import dev.ja.marketplace.client.*
import dev.ja.marketplace.data.customerType.CustomerTypeFactory
import dev.ja.marketplace.data.customers.ActiveCustomerTableFactory
import dev.ja.marketplace.data.customers.ChurnedCustomerTableFactory
import dev.ja.marketplace.data.customers.CustomerTableFactory
import dev.ja.marketplace.data.downloads.MonthlyDownloadsFactory
import dev.ja.marketplace.data.licenses.LicenseTable
import dev.ja.marketplace.data.licenses.LicenseTableFactory
import dev.ja.marketplace.data.overview.OverviewTableFactory
import dev.ja.marketplace.data.salesToday.SalesTodayFactory
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
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.pipeline.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MarketplaceStatsServer(
    private val client: MarketplaceClient,
    private val host: String = "0.0.0.0",
    private val port: Int = 8080
) {
    private lateinit var allPlugins: List<PluginInfoSummary>

    private val dataLoaders = ConcurrentHashMap<PluginId, PluginDataLoader>()

    private fun getDataLoader(plugin: PluginInfoSummary): PluginDataLoader {
        return dataLoaders.computeIfAbsent(plugin.id) {
            PluginDataLoader(plugin, client)
        }
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
            SalesTodayFactory(),
            TrialsTodayFactory(),
            CustomerTypeFactory(),
            TopCountriesFactory(),
            TopTrialCountriesFactory(),
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
        pageTitle = "Licenses"
    )

    private val countriesPageData: PluginPageDefinition = DefaultPluginPageDefinition(
        client,
        listOf(TopCountriesFactory(Int.MAX_VALUE)),
        pageTitle = "Countries"
    )

    private val allCustomersPageData: PluginPageDefinition = DefaultPluginPageDefinition(
        client,
        listOf(CustomerTableFactory()),
        pageCssClasses = "wide",
        pageTitle = "Customers (all)"
    )

    private val activeCustomersPageData: PluginPageDefinition = DefaultPluginPageDefinition(
        client,
        listOf(ActiveCustomerTableFactory()),
        pageCssClasses = "wide",
        pageTitle = "Customers With Active Licenses"
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
        listOf(TopTrialCountriesFactory(Int.MAX_VALUE)),
        pageTitle = "Countries of Trial Users",
    )

    private val monthlyDownloadsPageData: PluginPageDefinition = DefaultPluginPageDefinition(
        client,
        listOf(MonthlyDownloadsFactory()),
        pageTitle = "Downloads",
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
                val pluginId = context.request.queryParameters["pluginId"]?.toInt()
                if (pluginId != null) {
                    dataLoaders.remove(pluginId)
                } else {
                    dataLoaders.clear()
                }

                call.respondRedirect {
                    path("/")
                    if (pluginId != null) {
                        parameters {
                            append("pluginId", pluginId.toString())
                        }
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
                    call.respond(JteContent("main.kte", pageData.createTemplateParameters(loader)))
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
                call.respond(JteContent("main.kte", licensePageData.createTemplateParameters(loader)))
            }
            get("/countries") {
                val loader = getDataLoader()
                    ?: throw IllegalStateException("Unable to find plugin")
                call.respond(JteContent("main.kte", countriesPageData.createTemplateParameters(loader)))
            }
            get("/customers") {
                val loader = getDataLoader()
                    ?: throw IllegalStateException("Unable to find plugin")
                call.respond(JteContent("main.kte", allCustomersPageData.createTemplateParameters(loader)))
            }
            get("/customers/active") {
                val loader = getDataLoader()
                    ?: throw IllegalStateException("Unable to find plugin")
                call.respond(JteContent("main.kte", activeCustomersPageData.createTemplateParameters(loader)))
            }
            get("/customers/churned") {
                val loader = getDataLoader()
                    ?: throw IllegalStateException("Unable to find plugin")
                call.respond(JteContent("main.kte", churnedCustomersPageData.createTemplateParameters(loader)))
            }
            get("/trials") {
                val loader = getDataLoader()
                    ?: throw IllegalStateException("Unable to find plugin")
                call.respond(JteContent("main.kte", trialsPageData.createTemplateParameters(loader)))
            }
            get("/trials/countries") {
                val loader = getDataLoader()
                    ?: throw IllegalStateException("Unable to find plugin")
                call.respond(JteContent("main.kte", trialCountriesPageData.createTemplateParameters(loader)))
            }
            get("/downloads/monthly") {
                val loader = getDataLoader()
                    ?: throw IllegalStateException("Unable to find plugin")
                call.respond(JteContent("main.kte", monthlyDownloadsPageData.createTemplateParameters(loader)))
            }
            get("/customer/{id}") {
                val customerId: CustomerId = call.parameters["id"]?.toIntOrNull()
                    ?: throw IllegalStateException("unable to find customer id")
                val loader = getDataLoader()
                    ?: throw IllegalStateException("Unable to find plugin")
                val data = loader.loadCached()
                val customerInfo = data.sales?.firstOrNull { it.customer.code == customerId }?.customer
                    ?: data.trials?.firstOrNull { it.customer.code == customerId }?.customer
                    ?: throw IllegalStateException("Customer with id $customerId not found")

                val sales = data.sales?.filter { it.customer.code == customerId } ?: emptyList()
                val licenses = data.licenses?.filter { it.sale.customer.code == customerId } ?: emptyList()
                val trials = data.trials?.filter { it.customer.code == customerId } ?: emptyList()

                val licenseTableMonthly = LicenseTable(showDetails = false, showFooter = true) {
                    it.sale.licensePeriod == LicensePeriod.Monthly
                }

                val licenseTableAnnual = LicenseTable(showDetails = false, showFooter = true) {
                    it.sale.licensePeriod == LicensePeriod.Annual
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
        }
    }

    private val cacheResetExecutor = Executors.newSingleThreadScheduledExecutor()

    suspend fun start() {
        val userInfo = client.userInfo()
        this.allPlugins = client.plugins(userInfo.id).sortedBy { it.name }

        // preload in background
        if (allPlugins.isNotEmpty()) {
            CoroutineScope(Dispatchers.IO).launch {
                allPlugins.forEach {
                    getDataLoader(it).loadCached()
                }
            }
        }

        // install ticker to reset the cache every 30min
        cacheResetExecutor.scheduleWithFixedDelay(dataLoaders::clear, 0, 30, TimeUnit.MINUTES)

        println("Launching web server: http://$host:$port/")
        httpServer.start(true)
    }
}