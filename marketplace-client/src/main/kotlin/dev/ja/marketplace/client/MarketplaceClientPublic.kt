/*
 * Copyright (c) 2024-2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client

import dev.ja.marketplace.client.model.*
import java.nio.file.Path

/**
 * API available without an API key.
 */
interface MarketplaceClientPublic {
    suspend fun pluginInfo(plugin: PluginId): PluginInfo

    suspend fun pluginRating(id: PluginId): PluginRating

    /**
     * @return The ratings of the categories of the reviews, e.g. of the performance. Categories, which the API adds
     *  later, are an [UnknownRatingCategory].
     */
    suspend fun pluginRatingByCategory(id: PluginId): Map<PluginRatingCategory, PluginCategoryRating>

    /**
     * @return The results of the plugin verifier for a release of the plugin, one for each verified IDE.
     */
    suspend fun verificationResults(
        pluginReleaseId: PluginReleaseId,
        type: PluginVerificationType = PluginVerificationType.IntelliJCompatibility,
    ): List<PluginVerificationResult>

    /**
     * Fetches all available comments by executing several paged requests
     * @param pageSize Page size for a request for comments
     * @return Review comments about a plugin
     */
    suspend fun reviewComments(plugin: PluginId, pageSize: Int = 20): List<PluginReviewComment>

    /**
     * @param size Maximum number of comments to retrieve
     * @param page The page number to retrieve, `1` is the first page
     * @return Review comments about a plugin
     */
    suspend fun reviewCommentsSinglePage(plugin: PluginId, size: Int = 20, page: Int = 1): List<PluginReviewComment>

    /**
     * @param comment The ID of a review comment, e.g. of a comment of [reviewComments] with replies, see [PluginReviewComment.repliesCount].
     * @return The replies to the review comment, the oldest first
     */
    suspend fun reviewReplies(comment: PluginReviewId): List<PluginReviewComment>

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
     * Search for plugins, only a single page of results.
     * The Marketplace returns at most [Marketplace.MAX_SEARCH_RESULT_SIZE] results per page and only the first
     * [Marketplace.MAX_SEARCH_RESULT_WINDOW] results of a query (offset + page size), the reported total is capped
     * at the same value.
     */
    suspend fun marketplacePluginsSearchSinglePage(request: MarketplacePluginSearchRequest): MarketplacePluginSearchResponse

    /**
     * Search for plugins.
     * This method automatically retrieves paged results and returns the combined result set.
     *
     * The Marketplace only returns the first [Marketplace.MAX_SEARCH_RESULT_WINDOW] results of a query.
     * [MarketplacePluginSearchRequest.searchMode] defines, if the results are limited to them
     * ([PluginSearchMode.Marketplace], in the requested order), or if all results are returned
     * ([PluginSearchMode.AnySizeWorkaround], in the order of disjoint queries by tag, if the query has more results).
     */
    suspend fun marketplacePluginsSearch(
        request: MarketplacePluginSearchRequest,
        pageSize: Int = Marketplace.MAX_SEARCH_RESULT_SIZE,
    ): List<MarketplacePluginSearchResultItem>

    /**
     * @return All tags of the Marketplace, of all product families.
     */
    suspend fun marketplaceTags(): List<PluginTag>

    suspend fun pluginReleaseDependencies(pluginReleaseId: PluginReleaseId): List<PluginDependency>

    suspend fun unsupportedReleaseProducts(pluginReleaseId: PluginReleaseId): List<PluginUnsupportedProduct>

    /**
     * @return List of developers associated with the plugin.
     */
    suspend fun pluginDevelopers(plugin: PluginId): List<JetBrainsAccountInfo>

    /**
     * The vendors are public, they don't need an API key.
     * @return The vendors, which the user is a member of, e.g. organizations and the personal vendor of the user,
     * like the page of an author at the Marketplace. A user may be a member of a vendor without developing one of its plugins.
     */
    suspend fun userVendors(userId: UserId): List<PluginVendor>

    /**
     * The features are public, they don't need an API key.
     * @return The features of the plugin, e.g. the supported file types, see [PluginFeature.FILE_TYPE].
     */
    suspend fun pluginFeatures(plugin: PluginId): List<PluginFeature>

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