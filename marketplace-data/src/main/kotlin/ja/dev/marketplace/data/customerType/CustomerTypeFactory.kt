package ja.dev.marketplace.data.customerType

import ja.dev.marketplace.client.MarketplaceClient
import ja.dev.marketplace.data.MarketplaceDataSink
import ja.dev.marketplace.data.MarketplaceDataSinkFactory

class CustomerTypeFactory : MarketplaceDataSinkFactory {
    override fun createTableSink(client: MarketplaceClient): MarketplaceDataSink {
        return CustomerTypeTable()
    }
}