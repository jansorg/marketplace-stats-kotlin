/*
 * Copyright (c) 2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client

import java.nio.file.Path

/**
 * API available without an API key.
 */
interface MarketplaceClientPublic {
    suspend fun pluginInfo(plugin: PluginId): PluginInfo

    suspend fun pluginRating(id: PluginId): PluginRating

    /**
     * @return Review comments about a plugin
     */
    suspend fun reviewComments(plugin: PluginId): List<PluginReviewComment>

    /**
     * @return Review comments about a plugin
     */
    suspend fun reviewReplies(plugin: PluginId): List<PluginReviewComment>

    /**
     * @return All available releases of the plugin, sorted by date in descending order.
     */
    suspend fun releases(
        plugin: PluginId,
        channel: PluginChannel = PluginChannelNames.Stable,
        pageSize: Int = 16,
    ): List<PluginReleaseInfo>

    /**
     * @param page Page to fetch, `1` is the first page.
     * @return Releases of the plugin, sorted by date in descending order.
     */
    suspend fun releasesSinglePage(
        plugin: PluginId,
        channel: PluginChannel = PluginChannelNames.Stable,
        size: Int = 16,
        page: Int = 1
    ): List<PluginReleaseInfo>

    /**
     * @return The list of JetBrains products, which are compatible with the given plugin.
     */
    suspend fun compatibleProducts(plugin: PluginId): List<JetBrainsProductId>

    /**
     * @return The release channels available for the given [plugin]. An empty value indicates the stable, default channel.
     */
    suspend fun channels(plugin: PluginId): List<PluginChannel>

    /**
     * Search for plugins.
     * This method automatically retrieves paged results and returns the combined result set.
     */
    suspend fun marketplacePluginsSearchSinglePage(request: MarketplacePluginSearchRequest): MarketplacePluginSearchResponse

    /**
     * Search for plugins.
     * This method automatically retrieves paged results and returns the combined result set.
     */
    suspend fun marketplacePluginsSearch(
        request: MarketplacePluginSearchRequest,
        pageSize: Int = 100
    ): List<MarketplacePluginSearchResultItem>

    suspend fun pluginReleaseDependencies(pluginReleaseId: PluginReleaseId): List<PluginDependency>

    suspend fun unsupportedReleaseProducts(pluginReleaseId: PluginReleaseId): List<PluginUnsupportedProduct>

    /**
     * @return List of developers associated with the plugin.
     */
    suspend fun pluginDevelopers(plugin: PluginId): List<JetBrainsAccountInfo>

    suspend fun downloadRelease(target: Path, update: PluginReleaseInfo)

    suspend fun downloadRelease(target: Path, update: PluginReleaseId)

    suspend fun downloadRelease(target: Path, plugin: PluginId, version: String, channel: String?)

    /**
     * @param plugin ID of a paid plugin
     * @param fullInfo If `true`, then the result will contain the major release versions. This parameter can only be used for paid plugins of the API key's user.
     * @return The public plugin information of the given plugin.
     */
    @PaidPluginAPI
    suspend fun marketplacePluginInfo(plugin: PluginId, fullInfo: Boolean = false): MarketplacePluginInfo

    @PaidPluginAPI
    suspend fun marketplacePrograms(plugin: PluginId): List<MarketplaceProgram>

    @PaidPluginAPI
    suspend fun priceInfo(plugin: PluginId, isoCountryCode: String): PluginPriceInfo
}