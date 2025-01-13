/*
 * Copyright (c) 2023-2025 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client

import dev.ja.marketplace.client.model.*
import dev.ja.marketplace.services.JetBrainsProductCode
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.utils.io.*
import io.ktor.utils.io.core.*
import io.ktor.utils.io.jvm.nio.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.math.min

open class KtorMarketplaceClient(
    private val apiHost: String = Marketplace.HOSTNAME,
    private val apiPath: String = Marketplace.API_PATH,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(2),
    private val httpClient: HttpClient,
) : MarketplaceClient {
    @Suppress("unused")
    constructor(
        apiKey: String,
        apiHost: String = Marketplace.HOSTNAME,
        apiPath: String = Marketplace.API_PATH,
        logLevel: ClientLogLevel = ClientLogLevel.None,
        enableHttpCaching: Boolean = false,
        dispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(2),
    ) : this(
        apiHost,
        apiPath,
        dispatcher,
        KtorHttpClientFactory.createHttpClient(apiHost, apiKey, logLevel = logLevel, enableHttpCaching = enableHttpCaching)
    )

    override fun assetUrl(path: String): String {
        return "https://$apiHost/${path.removePrefix("/")}"
    }

    override suspend fun userInfo(): UserInfo = withContext(dispatcher) {
        httpClient.get("${apiPath}/users/me/full").body()
    }

    override suspend fun plugins(
        userId: UserId,
        family: List<ProductFamily>?,
        page: Int,
        maxResults: Int?
    ): List<PluginInfoSummary> = withContext(dispatcher) {
        httpClient.get("${apiPath}/users/$userId/plugins") {
            parameter("page", page)

            if (maxResults != null) {
                parameter("size", maxResults)
            }

            url {
                if (family != null) {
                    parameters.appendAll("family", family.map(ProductFamily::jsonId))
                }
            }
        }.body()
    }

    override suspend fun pluginInfo(plugin: PluginId): PluginInfo = withContext(dispatcher) {
        httpClient.get("${apiPath}/plugins/$plugin").body()
    }

    override suspend fun pluginRating(id: PluginId): PluginRating = withContext(dispatcher) {
        httpClient.get("${apiPath}/plugins/$id/rating").body()
    }

    override suspend fun salesInfo(plugin: PluginId): List<PluginSale> = withContext(dispatcher) {
        salesInfo(plugin, Marketplace.Birthday rangeTo YearMonthDay.now())
    }

    override suspend fun salesInfo(plugin: PluginId, range: YearMonthDayRange): List<PluginSale> = withContext(dispatcher) {
        // fetch in smaller chunks to avoid gateway timeouts and high load on the JetBrains sales API
        range.stepSequence(months = 1)
            .asFlow()
            .map { loadSalesInfo(plugin, it) }
            .toList()
            .flatten()
            .sorted()
    }

    override suspend fun licenseInfo(plugin: PluginId): SalesWithLicensesInfo = withContext(dispatcher) {
        val sales = salesInfo(plugin)
        SalesWithLicensesInfo(sales, LicenseInfo.createFrom(sales))
    }

    override suspend fun trialsInfo(plugin: PluginId): List<PluginTrial> = withContext(dispatcher) {
        trialsInfo(plugin, Marketplace.Birthday.rangeTo(YearMonthDay.now()))
    }

    override suspend fun trialsInfo(plugin: PluginId, range: YearMonthDayRange): List<PluginTrial> = withContext(dispatcher) {
        // smaller chunk size because of gateway timeouts when a year was used
        range.stepSequence(months = 1)
            .asFlow()
            .map { loadTrialsInfo(plugin, it) }.toList()
            .flatten()
            .sorted()
    }

    override suspend fun downloadsTotal(plugin: PluginId, vararg filters: DownloadFilter): Long = withContext(dispatcher) {
        val response = httpClient.get("/statistic/downloads-count/plugin") {
            parameter("plugin", plugin)
            addDownloadFilters(filters)
        }.body<DownloadResponse>()

        assert(response.dimension == DownloadDimension.Plugin)
        assert(response.data.dimension == DownloadDimension.Plugin)

        response.data.serie.single().value
    }

    override suspend fun downloads(
        plugin: PluginId,
        groupType: DownloadRequestDimension,
        countType: DownloadCountType,
        startDate: YearMonthDay?,
        vararg filters: DownloadFilter
    ): DownloadResponse = withContext(dispatcher) {
        httpClient.get("/statistic/${countType.requestPathSegment}/${groupType.requestPathSegment}") {
            parameter("plugin", plugin)
            if (startDate != null) {
                parameter("startDate", startDate.asIsoString)
            }
            addDownloadFilters(filters)
        }.body<DownloadResponse>()
    }

    override suspend fun downloadsMonthly(
        plugin: PluginId,
        countType: DownloadCountType,
        startDate: YearMonthDay?,
        productCode: JetBrainsProductCode?
    ): List<MonthlyDownload> = withContext(dispatcher) {
        val filters = when {
            productCode != null -> arrayOf(DownloadFilter.productCode(productCode))
            else -> emptyArray()
        }

        val response = downloads(plugin, DownloadRequestDimension.Month, countType, startDate, *filters)
        assert(response.dimension == DownloadDimension.Month)
        assert(response.data.dimension == DownloadDimension.Month)

        response.data.serie
            .map { MonthlyDownload(YearMonthDay.parse(it.name), it.value) }
            .sortedBy { it.firstOfMonth }
    }

    override suspend fun downloadsDaily(
        plugin: PluginId,
        countType: DownloadCountType,
        startDate: YearMonthDay?,
        productCode: JetBrainsProductCode?
    ): List<DailyDownload> = withContext(dispatcher) {
        val filters = when {
            productCode != null -> arrayOf(DownloadFilter.productCode(productCode))
            else -> emptyArray()
        }

        val response = downloads(plugin, DownloadRequestDimension.Day, countType, startDate, *filters)
        assert(response.dimension == DownloadDimension.Day)
        assert(response.data.dimension == DownloadDimension.Day)

        response.data.serie
            .map { DailyDownload(YearMonthDay.parse(it.name), it.value) }
            .sortedBy { it.day }
    }

    override suspend fun downloadsByProduct(plugin: PluginId, countType: DownloadCountType): List<ProductDownload> {
        return withContext(dispatcher) {
            val response = downloads(plugin, DownloadRequestDimension.ProductCode, countType)
            assert(response.dimension == DownloadDimension.ProductCode)
            assert(response.data.dimension == DownloadDimension.ProductCode)

            response.data.serie.map {
                ProductDownload(
                    JetBrainsProductCode.byProductCode(it.name) ?: throw IllegalStateException("No product code found for ${it.name}"),
                    it.comment,
                    it.value
                )
            }.sortedBy(ProductDownload::productName)
        }
    }

    override suspend fun compatibleProducts(plugin: PluginId): List<JetBrainsProductId> = withContext(dispatcher) {
        httpClient.get("${apiPath}/plugins/${plugin}/compatible-products").body()
    }

    override suspend fun volumeDiscounts(plugin: PluginId): VolumeDiscountResponse = withContext(dispatcher) {
        httpClient.get("${apiPath}/marketplace/plugin/${plugin}/volume-discounts").body()
    }

    override suspend fun marketplacePluginInfo(plugin: PluginId, fullInfo: Boolean): MarketplacePluginInfo = withContext(dispatcher) {
        httpClient.get("${apiPath}/marketplace/plugin/${plugin}") {
            parameter("fullInfo", fullInfo)
        }.body()
    }

    override suspend fun marketplacePluginsSearchSinglePage(request: MarketplacePluginSearchRequest): MarketplacePluginSearchResponse {
        return withContext(dispatcher) {
            httpClient.get("${apiPath}/searchPlugins") {
                parameter("max", request.maxResults ?: Marketplace.MAX_SEARCH_RESULT_SIZE)
                parameter("offset", request.offset)

                if (!request.queryFilter.isNullOrEmpty()) {
                    parameter("search", request.queryFilter)
                }

                if (request.orderBy?.parameterValue != null) {
                    parameter("orderBy", request.orderBy.parameterValue)
                }

                if (request.shouldHaveSource != null) {
                    parameter("shouldHaveSource", request.shouldHaveSource)
                }

                if (request.isFeaturedSearch != null) {
                    parameter("isFeaturedSearch", request.isFeaturedSearch)
                }

                url {
                    if (request.products != null) {
                        parameters.appendAll("products", request.products.map(PluginSearchProductId::parameterValue))
                    }

                    if (request.requiredTags.isNotEmpty()) {
                        parameters.appendAll("tags", request.requiredTags)
                    }

                    if (request.excludeTags.isNotEmpty()) {
                        parameters.appendAll("excludeTags", request.excludeTags)
                    }

                    if (!request.pricingModels.isNullOrEmpty()) {
                        parameters.appendAll("pricingModels", request.pricingModels.map(PluginPricingModel::searchQueryValue))
                    }
                }
            }.body()
        }
    }

    override suspend fun marketplacePluginsSearch(
        request: MarketplacePluginSearchRequest,
        pageSize: Int
    ): List<MarketplacePluginSearchResultItem> {
        return withContext(dispatcher) {
            val completeResult = mutableListOf<MarketplacePluginSearchResultItem>()

            var pendingResultSize = request.maxResults ?: Int.MAX_VALUE
            var offset = request.offset
            while (pendingResultSize > 0) {
                val resultPage = marketplacePluginsSearchSinglePage(
                    request.copy(
                        offset = offset,
                        maxResults = min(pageSize, pendingResultSize)
                    )
                )
                if (resultPage.searchResult.isEmpty()) {
                    break
                }

                pendingResultSize -= resultPage.searchResult.size
                offset += resultPage.searchResult.size
                completeResult += resultPage.searchResult
            }

            completeResult
        }
    }

    override suspend fun reviewComments(plugin: PluginId, pageSize: Int): List<PluginReviewComment> = withContext(dispatcher) {
        val completeResult = mutableListOf<PluginReviewComment>()
        var page = 1
        do {
            val lastPageResult = reviewCommentsSinglePage(plugin, size = pageSize, page = page++)
            completeResult += lastPageResult

            if (lastPageResult.isEmpty() || lastPageResult.size < pageSize) {
                break
            }
        } while (true)

        completeResult
    }

    override suspend fun reviewCommentsSinglePage(plugin: PluginId, size: Int, page: Int): List<PluginReviewComment> =
        withContext(dispatcher) {
            assert(size >= 1)
            assert(page >= 1)

            httpClient.get("${apiPath}/plugins/${plugin}/comments") {
                parameter("size", size)
                parameter("page", page)
            }.body()
        }

    override suspend fun reviewReplies(plugin: PluginId): List<PluginReviewComment> = withContext(dispatcher) {
        httpClient.get("${apiPath}/comments/$plugin/replies").body()
    }

    override suspend fun channels(plugin: PluginId): List<PluginChannel> = withContext(dispatcher) {
        httpClient.get("${apiPath}/plugins/${plugin}/channels").body()
    }

    override suspend fun releases(plugin: PluginId, channel: PluginChannel, pageSize: Int): List<PluginReleaseInfo> {
        return withContext(dispatcher) {
            val completeResult = mutableListOf<PluginReleaseInfo>()
            var page = 1
            do {
                val lastPageResult = releasesSinglePage(plugin, channel, size = pageSize, page = page)
                completeResult += lastPageResult
                page++

                if (lastPageResult.isEmpty() || lastPageResult.size < pageSize) {
                    break
                }
            } while (true)

            completeResult
        }
    }

    override suspend fun releasesSinglePage(plugin: PluginId, channel: PluginChannel, size: Int, page: Int): List<PluginReleaseInfo> {
        return withContext(dispatcher) {
            assert(size >= 1)
            assert(page >= 1)

            httpClient.get("${apiPath}/plugins/${plugin}/updates") {
                parameter("channel", channel)
                parameter("size", size)
                parameter("page", page)
            }.body()
        }
    }

    override suspend fun pluginReleaseDependencies(pluginReleaseId: PluginReleaseId): List<PluginDependency> = withContext(dispatcher) {
        httpClient.get("$apiPath/updates/$pluginReleaseId/dependencies").body()
    }

    override suspend fun unsupportedReleaseProducts(pluginReleaseId: PluginReleaseId): List<PluginUnsupportedProduct> =
        withContext(dispatcher) {
            httpClient.get("$apiPath/products-dependencies/updates/$pluginReleaseId/unsupported").body()
        }

    override suspend fun pluginDevelopers(plugin: PluginId): List<JetBrainsAccountInfo> = withContext(dispatcher) {
        httpClient.get("$apiPath/plugins/$plugin/developers").body()
    }

    override suspend fun downloadRelease(target: Path, update: PluginReleaseInfo) {
        target.streamingDownload(update.getMarketplaceDownloadLink())
    }

    override suspend fun downloadRelease(target: Path, update: PluginReleaseId) {
        target.streamingDownload(URLBuilder(assetUrl("/plugin/download")).apply {
            parameters.append("updateId", update.toString())
        }.build())
    }

    override suspend fun downloadRelease(target: Path, plugin: PluginId, version: String, channel: String?) {
        target.streamingDownload(URLBuilder(assetUrl("/plugin/download")).apply {
            parameters.append("pluginId", plugin.toString())
            parameters.append("version", version)
            if (channel != null) {
                parameters.append("channel", channel)
            }
        }.build())
    }

    override suspend fun priceInfo(plugin: PluginId, isoCountryCode: String): PluginPriceInfo = withContext(dispatcher) {
        httpClient.get("${apiPath}/marketplace/plugin/${plugin}/prices") {
            parameter("countryCode", isoCountryCode)
        }.body()
    }

    override suspend fun marketplacePrograms(plugin: PluginId): List<MarketplaceProgram> = withContext(dispatcher) {
        httpClient.get("$apiPath/marketplace/plugin/$plugin/programs").body()
    }

    private suspend fun Path.streamingDownload(url: Url): Unit = withContext(dispatcher) {
        if (Files.exists(this@streamingDownload)) {
            throw IllegalArgumentException("File ${this@streamingDownload} already exists, unable to download $url.")
        }

        Files.newByteChannel(this@streamingDownload, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { outputChannel ->
            httpClient.prepareGet(url).execute { httpResponse ->
                httpResponse.body<ByteReadChannel>().copyTo(outputChannel)
            }
        }
    }

    protected open suspend fun loadSalesInfo(plugin: PluginId, range: YearMonthDayRange): List<PluginSale> = withContext(dispatcher) {
        httpClient.get("$apiPath/marketplace/plugin/$plugin/sales-info") {
            parameter("beginDate", range.start.asIsoString)
            parameter("endDate", range.end.asIsoString)
        }.body()
    }

    protected open suspend fun loadTrialsInfo(plugin: PluginId, range: YearMonthDayRange): List<PluginTrial> = withContext(dispatcher) {
        httpClient.get("$apiPath/marketplace/plugin/$plugin/trials-info") {
            parameter("beginDate", range.start.asIsoString)
            parameter("endDate", range.end.asIsoString)
        }.body()
    }

    private fun HttpRequestBuilder.addDownloadFilters(filters: Array<out DownloadFilter>) {
        filters.forEach { filter ->
            parameter(filter.type.requestParameterName, filter.value)
        }
    }
}