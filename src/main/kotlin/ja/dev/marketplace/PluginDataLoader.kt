package ja.dev.marketplace

import ja.dev.marketplace.client.MarketplaceClient
import ja.dev.marketplace.client.PluginId
import ja.dev.marketplace.client.PluginInfo
import ja.dev.marketplace.client.PluginSale
import ja.dev.marketplace.data.LicenseInfo
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.atomic.AtomicReference

data class PluginData(
    val pluginId: PluginId,
    val pluginInfo: PluginInfo,
    val sales: List<PluginSale>,
    val licenses: List<LicenseInfo>
)

class PluginDataLoader(private val pluginId: PluginId, private val client: MarketplaceClient) {
    private val cachedData = AtomicReference<PluginData>()

    suspend fun loadCached(): PluginData {
        return when (val cached = cachedData.get()) {
            null -> {
                val data = load()
                cachedData.set(data)
                data
            }

            else -> cached
        }
    }

    private suspend fun load(): PluginData {
        return coroutineScope {
            val pluginInfo = async { client.pluginInfo(pluginId) }
            val sales = async { client.salesInfo(pluginId) }
            val licenseInfo = async { LicenseInfo.create(sales.await()) }

            PluginData(pluginId, pluginInfo.await(), sales.await(), licenseInfo.await())
        }
    }
}