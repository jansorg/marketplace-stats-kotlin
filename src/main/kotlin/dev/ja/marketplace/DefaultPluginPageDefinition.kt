/*
 * Copyright (c) 2023-2025 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace

import dev.ja.marketplace.client.MarketplaceClient
import dev.ja.marketplace.client.PluginChannelNames
import dev.ja.marketplace.client.MarketplaceUrlSupport
import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.data.DataTable
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
) : PluginPageDefinition {
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

        // process data in sinks concurrently
        val dataSinks = factories.map { it.createTable(client, maxTableRows) }
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

        mapOf(
            "pageTitle" to pageTitle,
            "pageDescription" to pageDescription,
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
