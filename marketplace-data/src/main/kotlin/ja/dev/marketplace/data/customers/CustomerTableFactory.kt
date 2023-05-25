package ja.dev.marketplace.data.customers

import ja.dev.marketplace.client.MarketplaceClient
import ja.dev.marketplace.client.YearMonthDay
import ja.dev.marketplace.data.MarketplaceDataSink
import ja.dev.marketplace.data.MarketplaceDataSinkFactory

class CustomerTableFactory : MarketplaceDataSinkFactory {
    override fun createTableSink(client: MarketplaceClient): MarketplaceDataSink {
        return CustomerTable { true }
    }
}

class ActiveCustomerTableFactory : MarketplaceDataSinkFactory {
    override fun createTableSink(client: MarketplaceClient): MarketplaceDataSink {
        val now = YearMonthDay.now()
        return CustomerTable { licenseInfo -> now in licenseInfo.validity }
    }
}