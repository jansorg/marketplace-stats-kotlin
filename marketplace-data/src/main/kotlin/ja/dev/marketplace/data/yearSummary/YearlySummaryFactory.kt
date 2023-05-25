package ja.dev.marketplace.data.yearSummary

import ja.dev.marketplace.client.MarketplaceClient
import ja.dev.marketplace.data.MarketplaceDataSink
import ja.dev.marketplace.data.MarketplaceDataSinkFactory

class YearlySummaryFactory : MarketplaceDataSinkFactory {
    override fun createTableSink(client: MarketplaceClient): MarketplaceDataSink {
        return YearlySummaryTable()
    }
}