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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

class MarketplaceStatsServer(pluginId: PluginId, client: MarketplaceClient) {
    private val dataLoader = PluginDataLoader(pluginId, client)

    private val indexPageData: PluginPageDefinition = DefaultPluginPageDefinition(
        client, dataLoader, listOf(
            YearlySummaryFactory(),
            CurrentWeekFactory(),
            TopCountriesFactory(),
            TopTrialCountriesFactory(),
            OverviewTableFactory()
        )
    )

    private val licensePageData: PluginPageDefinition = DefaultPluginPageDefinition(
        client, dataLoader, listOf(LicenseTableFactory())
    )

    private val countriesPageData: PluginPageDefinition = DefaultPluginPageDefinition(
        client, dataLoader, listOf(TopCountriesFactory(Int.MAX_VALUE))
    )

    private val allCustomersPageData: PluginPageDefinition = DefaultPluginPageDefinition(
        client, dataLoader, listOf(CustomerTableFactory()), pageCssClasses = "wide"
    )

    private val activeCustomersPageData: PluginPageDefinition = DefaultPluginPageDefinition(
        client, dataLoader, listOf(ActiveCustomerTableFactory()), pageCssClasses = "wide"
    )

    private val trialsPageData: PluginPageDefinition = DefaultPluginPageDefinition(
        client, dataLoader, listOf(TrialsTableFactory()), pageCssClasses = "wide"
    )

    private val trialCountriesPageData: PluginPageDefinition = DefaultPluginPageDefinition(
        client, dataLoader, listOf(TopTrialCountriesFactory(Int.MAX_VALUE))
    )

    private val monthlyDownloadsPageData: PluginPageDefinition = DefaultPluginPageDefinition(
        client, dataLoader, listOf(MonthlyDownloadsFactory())
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
                call.respond(JteContent("main.kte", indexPageData.createTemplateParameters()))
            }
            get("/licenses") {
                call.respond(JteContent("main.kte", licensePageData.createTemplateParameters()))
            }
            get("/countries") {
                call.respond(JteContent("main.kte", countriesPageData.createTemplateParameters()))
            }
            get("/customers") {
                call.respond(JteContent("main.kte", allCustomersPageData.createTemplateParameters()))
            }
            get("/customers/active") {
                call.respond(JteContent("main.kte", activeCustomersPageData.createTemplateParameters()))
            }
            get("/trials") {
                call.respond(JteContent("main.kte", trialsPageData.createTemplateParameters()))
            }
            get("/trials/countries") {
                call.respond(JteContent("main.kte", trialCountriesPageData.createTemplateParameters()))
            }
            get("/downloads/monthly") {
                call.respond(JteContent("main.kte", monthlyDownloadsPageData.createTemplateParameters()))
            }
        }
    }

    suspend fun start() {
        coroutineScope {
            launch {
                dataLoader.loadCached()
            }
        }
        httpServer.start(true)
    }
}