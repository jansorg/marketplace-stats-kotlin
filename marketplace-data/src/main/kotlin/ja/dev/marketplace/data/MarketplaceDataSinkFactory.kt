package ja.dev.marketplace.data

import ja.dev.marketplace.client.MarketplaceClient

interface MarketplaceDataSinkFactory {
    fun createTableSink(client: MarketplaceClient): MarketplaceDataSink
}