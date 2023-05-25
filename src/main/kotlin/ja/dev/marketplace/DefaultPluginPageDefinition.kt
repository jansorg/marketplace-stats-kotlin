package ja.dev.marketplace

import ja.dev.marketplace.client.MarketplaceClient
import ja.dev.marketplace.data.DataTable
import ja.dev.marketplace.data.MarketplaceDataSinkFactory
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList

class DefaultPluginPageDefinition(
    private val client: MarketplaceClient,
    private val dataLoader: PluginDataLoader,
    private val factories: List<MarketplaceDataSinkFactory>,
    private val pageCssClasses: String? = null,
) : PluginPageDefinition {
    override suspend fun createTemplateParameters(): Map<String, Any?> {
        val data = dataLoader.loadCached()

        // process data in sinks concurrently
        val dataSinks = factories.map { it.createTableSink(client) }
            .asFlow()
            .onEach { table ->
                table.init()
                data.sales.forEach(table::process)
                data.licenses.forEach(table::process)
            }.toList()

        return mapOf(
            "plugin" to data.pluginInfo,
            "tables" to dataSinks.filterIsInstance<DataTable>(),
            "cssClass" to pageCssClasses,
        )
    }
}
