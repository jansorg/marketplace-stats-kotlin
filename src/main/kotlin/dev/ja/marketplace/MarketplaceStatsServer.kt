/*
 * Copyright (c) 2023 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace

import dev.ja.marketplace.client.MarketplaceClient
import dev.ja.marketplace.client.PluginId
import dev.ja.marketplace.data.currentWeek.CurrentWeekFactory
import dev.ja.marketplace.data.customers.ActiveCustomerTableFactory
import dev.ja.marketplace.data.customers.CustomerTableFactory
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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class MarketplaceStatsServer(pluginIds: List<PluginId>, client: MarketplaceClient) {
    private val dataLoaders = pluginIds.map { PluginDataLoader(it, client) }
    private val pluginInfos = runBlocking { dataLoaders.map { it.loadCached().pluginInfo } }

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


            pluginIds.forEachIndexed { index, pluginId ->
                val dataLoader = dataLoaders[index]

                val indexPageData: PluginPageDefinition = DefaultPluginPageDefinition(
                    pluginInfos, client, dataLoader, listOf(
                        YearlySummaryFactory(),
                        CurrentWeekFactory(),
                        TopCountriesFactory(),
                        TopTrialCountriesFactory(),
                        OverviewTableFactory()
                    )
                )
                val licensePageData: PluginPageDefinition = DefaultPluginPageDefinition(
                    pluginInfos, client, dataLoader, listOf(LicenseTableFactory())
                )
                val countriesPageData: PluginPageDefinition = DefaultPluginPageDefinition(
                    pluginInfos, client, dataLoader, listOf(TopCountriesFactory(Int.MAX_VALUE))
                )
                val allCustomersPageData: PluginPageDefinition = DefaultPluginPageDefinition(
                    pluginInfos, client, dataLoader, listOf(CustomerTableFactory()), pageCssClasses = "wide"
                )
                val activeCustomersPageData: PluginPageDefinition = DefaultPluginPageDefinition(
                    pluginInfos, client, dataLoader, listOf(ActiveCustomerTableFactory()), pageCssClasses = "wide"
                )
                val trialsPageData: PluginPageDefinition = DefaultPluginPageDefinition(
                    pluginInfos, client, dataLoader, listOf(TrialsTableFactory()), pageCssClasses = "wide"
                )
                val trialCountriesPageData: PluginPageDefinition = DefaultPluginPageDefinition(
                    pluginInfos, client, dataLoader, listOf(TopTrialCountriesFactory(Int.MAX_VALUE))
                )

                if (index == 0) {
                    get("/") {
                        call.respond(JteContent("main.kte", indexPageData.createTemplateParameters()))
                    }
                }

                get("/$pluginId/") {
                    call.respond(JteContent("main.kte", indexPageData.createTemplateParameters()))
                }
                get("/$pluginId/licenses") {
                    call.respond(JteContent("main.kte", licensePageData.createTemplateParameters()))
                }
                get("/$pluginId/countries") {
                    call.respond(JteContent("main.kte", countriesPageData.createTemplateParameters()))
                }
                get("/$pluginId/customers") {
                    call.respond(JteContent("main.kte", allCustomersPageData.createTemplateParameters()))
                }
                get("/$pluginId/customers/active") {
                    call.respond(JteContent("main.kte", activeCustomersPageData.createTemplateParameters()))
                }
                get("/$pluginId/trials") {
                    call.respond(JteContent("main.kte", trialsPageData.createTemplateParameters()))
                }
                get("/$pluginId/trials/countries") {
                    call.respond(JteContent("main.kte", trialCountriesPageData.createTemplateParameters()))
                }
            }
        }
    }

    suspend fun start() {
        coroutineScope {
            launch {
                dataLoaders.forEach { it.loadCached() }
            }
        }
        httpServer.start(true)
    }
}