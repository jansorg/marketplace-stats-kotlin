/*
 * Copyright (c) 2023-2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace

import dev.ja.marketplace.client.MarketplaceClient
import dev.ja.marketplace.client.MarketplaceUrlSupport
import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.data.DataTable
import dev.ja.marketplace.data.MarketplaceDataTableFactory
import io.ktor.server.request.*
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
    override suspend fun createTemplateParameters(dataLoader: PluginDataLoader, request: ApplicationRequest): Map<String, Any?> {
        val data = dataLoader.load()

        val maxTableRows = request.queryParameters["rows"]?.toIntOrNull()

        // process data in sinks concurrently
        val dataSinks = factories.map { it.createTable(client, maxTableRows) }
            .asFlow()
            .onEach { table ->
                table.init(data)

                val sales = data.sales
                if (sales != null) {
                    for (sale in sales) {
                        table.process(sale)
                    }
                }

                val licenses = data.licenses
                if (licenses != null) {
                    for (license in licenses) {
                        table.process(license)
                    }
                }
            }
            .map(DataTable::renderTable)
            .toList()

        return mapOf(
            "pageTitle" to pageTitle,
            "pageDescription" to pageDescription,
            "today" to YearMonthDay.now(),
            "plugin" to data.pluginInfo,
            "rating" to data.pluginRating,
            "tables" to dataSinks,
            "cssClass" to pageCssClasses,
            "urls" to dataLoader.client as MarketplaceUrlSupport,
        )
    }
}