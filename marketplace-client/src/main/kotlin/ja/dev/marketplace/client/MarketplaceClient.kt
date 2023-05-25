package ja.dev.marketplace.client

interface MarketplaceClient {
    suspend fun pluginInfo(id: PluginId): PluginInfo

    suspend fun pluginRating(id: PluginId): PluginRating

    /**
     * @return all plugin sales since the inception of the marketplace until the current date
     */
    suspend fun salesInfo(plugin: PluginId): List<PluginSale>

    /**
     * @param range Date range of sales, inclusive
     * @return plugin sales during the given range
     */
    suspend fun salesInfo(plugin: PluginId, range: YearMonthDayRange): List<PluginSale>

    /**
     * @return all plugin trials since the inception of the marketplace until the current date
     */
    suspend fun trialsInfo(plugin: PluginId): List<PluginTrial>

    /**
     * @param plugin Plugin
     * @param range  Date range of trials to fetch, inclusive
     * @return plugin trials in the given range
     */
    suspend fun trialsInfo(plugin: PluginId, range: YearMonthDayRange): List<PluginTrial>

    // fixme support downloads
    //suspend fun downloadsMonthly(plugin: PluginId, uniqueDownloads: Boolean, channel)
}