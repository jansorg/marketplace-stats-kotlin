package ja.dev.marketplace

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
import ja.dev.marketplace.client.MarketplaceClient
import ja.dev.marketplace.client.PluginId
import ja.dev.marketplace.data.currentWeek.CurrentWeekFactory
import ja.dev.marketplace.data.customers.ActiveCustomerTableFactory
import ja.dev.marketplace.data.customers.CustomerTableFactory
import ja.dev.marketplace.data.licenses.LicenseTableFactory
import ja.dev.marketplace.data.overview.OverviewTableFactory
import ja.dev.marketplace.data.topCountries.TopCountriesFactory
import ja.dev.marketplace.data.yearSummary.YearlySummaryFactory
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

class MarketplaceStatsServer(pluginId: PluginId, client: MarketplaceClient) {
    private val dataLoader = PluginDataLoader(pluginId, client)

    private val indexPageData: PluginPageDefinition = DefaultPluginPageDefinition(
        client, dataLoader, listOf(
            YearlySummaryFactory(),
            CurrentWeekFactory(),
            TopCountriesFactory(),
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

    private val httpServer = embeddedServer(Netty, host = "127.0.0.1", port = 8080) {
        install(Compression)
        install(Jte) {
            templateEngine = TemplateEngine.create(ResourceCodeResolver("templates"), ContentType.Html).also {
                it.setTrimControlStructures(true)
            }
        }

        routing {
            staticResources("/styles", "styles")

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