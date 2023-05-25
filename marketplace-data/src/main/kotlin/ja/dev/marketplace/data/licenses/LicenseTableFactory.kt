package ja.dev.marketplace.data.licenses

import ja.dev.marketplace.client.MarketplaceClient
import ja.dev.marketplace.data.MarketplaceDataSink
import ja.dev.marketplace.data.MarketplaceDataSinkFactory

class LicenseTableFactory: MarketplaceDataSinkFactory {
    override fun createTableSink(client: MarketplaceClient): MarketplaceDataSink {
        return LicenseTable()
    }
}