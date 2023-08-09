/*
 * Copyright (c) 2023 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace

import dev.ja.marketplace.client.MarketplaceClient
import dev.ja.marketplace.client.MarketplaceUrlSupport
import dev.ja.marketplace.client.PluginId
import dev.ja.marketplace.client.PluginInfoSummary
import dev.ja.marketplace.data.currentWeek.CurrentWeekFactory
import dev.ja.marketplace.data.customers.ActiveCustomerTableFactory
import dev.ja.marketplace.data.customers.ChurnedCustomerTableFactory
import dev.ja.marketplace.data.customers.CustomerTableFactory
import dev.ja.marketplace.data.downloads.MonthlyDownloadsFactory
import dev.ja.marketplace.data.licenses.LicenseTableFactory
import dev.ja.marketplace.data.overview.OverviewTableFactory
import dev.ja.marketplace.data.topCountries.TopCountriesFactory
import dev.ja.marketplace.data.topTrialCountries.TopTrialCountriesFactory
import dev.ja.marketplace.data.trials.TrialsTableFactory
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
    private val host: String = "127.0.0.1",
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
            CurrentWeekFactory(),
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
        install(Compression)
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