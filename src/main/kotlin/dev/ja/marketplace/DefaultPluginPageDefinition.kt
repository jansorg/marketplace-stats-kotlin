/*
 * Copyright (c) 2023-2025 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace

import dev.ja.marketplace.client.MarketplaceClient
import dev.ja.marketplace.client.PluginChannelNames
import dev.ja.marketplace.client.MarketplaceUrlSupport
import dev.ja.marketplace.client.PluginId
import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.data.DataTable
import dev.ja.marketplace.data.PluginData
import dev.ja.marketplace.data.RenderedDataTable
import dev.ja.marketplace.data.MarketplaceDataTableFactory
import dev.ja.marketplace.data.releases.MajorRelease
import dev.ja.marketplace.data.releases.MajorReleases
import dev.ja.marketplace.data.prices.PriceChanges
import io.ktor.server.request.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList

class DefaultPluginPageDefinition(
    private val client: MarketplaceClient,
    private val factories: List<MarketplaceDataTableFactory>,
    private val pageCssClasses: String? = null,
    private val pageTitle: String? = null,
    private val pageDescription: String? = null,
    /** An addition to the [pageDescription], which depends on the plugin, e.g. a note about its prices. */
    private val pageNote: (suspend (PluginData) -> String?)? = null,
    /**
     * If the calculated tables are used again while the data of the plugin is the same, see [PluginData.dataVersion].
     * Tables, which load data when they're calculated, e.g. the prices, must not be cached.
     */
    private val cacheTables: Boolean = true,
    /** If tables without rows are left out. If all tables are empty, the first one is shown. */
    private val hideEmptyTables: Boolean = false,
) : PluginPageDefinition {
    /** The calculated tables by the plugin and the maximum number of rows. */
    private val tableCache = VersionedCache<Pair<PluginId, Int?>, List<RenderedDataTable>>()

    override suspend fun createTemplateParameters(
        dataLoader: PluginDataLoader,
        request: ApplicationRequest,
        serverConfiguration: ServerConfiguration
    ): Map<String, Any?> = coroutineScope {
        // shown in the charts, loaded concurrently with the data of the tables
        val majorReleases = async { loadMajorReleases(dataLoader) }
        val ideReleases = async { dataLoader.ideReleases.majorReleases() }
        val data = dataLoader.load()

        val maxTableRows = request.queryParameters["rows"]?.toIntOrNull()

        val dataSinks = when {
            cacheTables -> tableCache.get(data.pluginId to maxTableRows, data.dataVersion()) { createTables(data, maxTableRows) }
            else -> createTables(data, maxTableRows)
        }

        mapOf(
            "pageTitle" to pageTitle,
            "pageDescription" to listOfNotNull(pageDescription, pageNote?.invoke(data)).joinToString("<br>").ifEmpty { null },
            "today" to YearMonthDay.now(),
            "plugin" to data.getPluginInfo(),
            "otherPlugins" to dataLoader.otherPaidPlugins,
            "rating" to data.getPluginRating(),
            "tables" to dataSinks,
            "cssClass" to pageCssClasses,
            "urls" to dataLoader.client as MarketplaceUrlSupport,
            "settings" to serverConfiguration,
            "majorReleases" to majorReleases.await(),
            "ideReleases" to ideReleases.await(),
            "priceChanges" to (data.getSales()?.let(PriceChanges::find) ?: emptyList()),
            "customEvents" to serverConfiguration.customEvents(setOf(data.pluginId)),
        )
    }

    /** @return The tables of the page, the data is processed by the tables concurrently. */
    private suspend fun createTables(data: PluginData, maxTableRows: Int?): List<RenderedDataTable> {
        return factories.map { it.createTable(client, maxTableRows) }
            .asFlow()
            .onEach { table ->
                table.init(data)

                val sales = data.getSales()
                if (sales != null) {
                    for (sale in sales) {
                        table.process(sale)
                    }
                }

                val licenses = data.getLicenses()
                if (licenses != null) {
                    for (license in licenses) {
                        table.process(license)
                    }
                }
            }
            .map(DataTable::renderTable)
            .toList()
            .let { tables ->
                when {
                    hideEmptyTables -> tables.filterNot(RenderedDataTable::isEmpty).ifEmpty { tables.take(1) }
                    else -> tables
                }
            }
    }

    private suspend fun loadMajorReleases(dataLoader: PluginDataLoader): List<MajorRelease> {
        if (!dataLoader.plugin.isPaidOrFreemium) {
            return emptyList()
        }

        return try {
            MajorReleases.find(client.releases(dataLoader.plugin.id, PluginChannelNames.Stable, pageSize = 100))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // the releases are optional, the page must be rendered without them
            emptyList()
        }
    }
}
