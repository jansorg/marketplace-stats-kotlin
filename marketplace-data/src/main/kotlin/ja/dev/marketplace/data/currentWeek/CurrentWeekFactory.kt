package ja.dev.marketplace.data.currentWeek

import ja.dev.marketplace.client.MarketplaceClient
import ja.dev.marketplace.data.MarketplaceDataSink
import ja.dev.marketplace.data.MarketplaceDataSinkFactory

class CurrentWeekFactory: MarketplaceDataSinkFactory {
    override fun createTableSink(client: MarketplaceClient): MarketplaceDataSink {
        return CurrentWeekTable()
    }
}