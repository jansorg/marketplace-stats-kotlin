package ja.dev.marketplace.data.topCountries

import ja.dev.marketplace.client.MarketplaceClient
import ja.dev.marketplace.data.MarketplaceDataSink
import ja.dev.marketplace.data.MarketplaceDataSinkFactory

class TopCountriesFactory(private val maxCountries: Int = 10) : MarketplaceDataSinkFactory {
    override fun createTableSink(client: MarketplaceClient): MarketplaceDataSink {
        return TopCountriesTable(maxCountries)
    }
}