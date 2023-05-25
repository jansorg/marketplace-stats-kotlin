package ja.dev.marketplace.data

import ja.dev.marketplace.client.PluginSale

interface MarketplaceDataSink {
    fun init() {}

    fun process(sale: PluginSale) {}

    fun process(licenseInfo: LicenseInfo)
}
