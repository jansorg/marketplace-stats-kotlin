/*
 * Copyright (c) 2023 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace

import dev.ja.marketplace.client.MarketplaceClient
import dev.ja.marketplace.client.MarketplaceUrlSupport
import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.data.DataTable
import dev.ja.marketplace.data.MarketplaceDataSinkFactory
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList

class DefaultPluginPageDefinition(
    private val client: MarketplaceClient,
    private val factories: List<MarketplaceDataSinkFactory>,
    private val pageCssClasses: String? = null,
) : PluginPageDefinition {
    override suspend fun createTemplateParameters(dataLoader: PluginDataLoader): Map<String, Any?> {
        val data = dataLoader.loadCached()

        // process data in sinks concurrently
        val dataSinks = factories.map { it.createTableSink(client) }
            .asFlow()
            .onEach { table ->
                table.init(data)
                data.sales?.forEach(table::process)
                data.licenses?.forEach(table::process)
            }.toList()

        return mapOf(
            "today" to YearMonthDay.now(),
            "plugin" to data.pluginInfo,
            "rating" to data.pluginRating,
            "tables" to dataSinks.filterIsInstance<DataTable>(),
            "cssClass" to pageCssClasses,
            "urls" to dataLoader.client as MarketplaceUrlSupport,
        )
    }
}