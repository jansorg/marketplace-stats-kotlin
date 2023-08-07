/*
 * Copyright (c) 2023 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace

import dev.ja.marketplace.client.MarketplaceClient
import dev.ja.marketplace.client.PluginId
import dev.ja.marketplace.client.PluginInfoSummary
import dev.ja.marketplace.data.currentWeek.CurrentWeekFactory
import dev.ja.marketplace.data.customers.ActiveCustomerTableFactory
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
import gg.jte.resolve.ResourceCodeResolver
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.http.content.*
import io.ktor.server.jte.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.compression.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.pipeline.*
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class MarketplaceStatsServer(private val client: MarketplaceClient) {
    private lateinit var allPlugins: List<PluginInfoSummary>
    private var defaultPluginLoader: PluginDataLoader? = null

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
        client, listOf(LicenseTableFactory())
    )

    private val countriesPageData: PluginPageDefinition = DefaultPluginPageDefinition(
        client, listOf(TopCountriesFactory(Int.MAX_VALUE))
    )

    private val allCustomersPageData: PluginPageDefinition = DefaultPluginPageDefinition(
        client, listOf(CustomerTableFactory()), pageCssClasses = "wide"
    )

    private val activeCustomersPageData: PluginPageDefinition = DefaultPluginPageDefinition(
        client, listOf(ActiveCustomerTableFactory()), pageCssClasses = "wide"
    )

    private val trialsPageData: PluginPageDefinition = DefaultPluginPageDefinition(
        client, listOf(TrialsTableFactory()), pageCssClasses = "wide"
    )

    private val trialCountriesPageData: PluginPageDefinition = DefaultPluginPageDefinition(
        client, listOf(TopTrialCountriesFactory(Int.MAX_VALUE))
    )

    private val monthlyDownloadsPageData: PluginPageDefinition = DefaultPluginPageDefinition(
        client, listOf(MonthlyDownloadsFactory())
    )

    private val httpServer = embeddedServer(Netty, host = "127.0.0.1", port = 8080) {
        install(Compression)
        install(Jte) {
            templateEngine = TemplateEngine.create(ResourceCodeResolver("templates"), ContentType.Html).also {
                it.setTrimControlStructures(true)
                it.prepareForRendering("main.kte")
            }
        }

        routing {
            staticResources("/styles", "styles")
            staticResources("/js", "js")

            get("/") {
                val loader = getDataLoader()
                if (loader != null || allPlugins.size == 1) {
                    if (loader?.plugin?.isPaidOrFreemium == true) {
                        call.respond(JteContent("main.kte", indexPageData.createTemplateParameters(loader)))
                    } else {
                        call.respond(
                            JteContent(
                                "main.kte",
                                indexPageDataFree.createTemplateParameters(loader ?: defaultPluginLoader!!)
                            )
                        )
                    }
                } else {
                    call.respond(JteContent("plugins.kte", mapOf("plugins" to allPlugins)))
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

    suspend fun start() {
        val userInfo = client.userInfo()

        this.allPlugins = client.plugins(userInfo.id)
            .sortedBy { it.name }

        this.defaultPluginLoader = this.allPlugins.firstOrNull()?.let { getDataLoader(it) }

        // preload the first plugin
        if (allPlugins.isNotEmpty()) {
            coroutineScope {
                launch {
                    getDataLoader(allPlugins[0]).loadCached()
                }
            }
        }

        httpServer.start(true)
    }
}