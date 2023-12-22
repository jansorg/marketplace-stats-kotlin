/*
 * Copyright (c) 2023 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.java.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.plugins.resources.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.Json

class KtorMarketplaceClient(
    private val apiKey: String,
    private val apiHost: String = "plugins.jetbrains.com",
    private val apiPath: String = "api",
    private val logLevel: ClientLogLevel = ClientLogLevel.None,
) : MarketplaceClient {
    private val httpClient = HttpClient(Java) {
        install(Logging) {
            level = logLevel.ktorLogLevel
        }
        install(Resources)
        install(ContentNegotiation) {
            json(Json {
                prettyPrint = true
                ignoreUnknownKeys = true
                // to support parsing `Amount` floats as BigDecimal
                isLenient = true
            })
        }

        install(DefaultRequest) {
            url {
                protocol = URLProtocol.HTTPS
                host = apiHost
            }
            bearerAuth(apiKey)
            header("Content-Type", "application/json")
        }

        expectSuccess = true
        engine {
            pipelining = true
        }
    }

    override fun assetUrl(path: String): String {
        return "https://$apiHost/${path.removePrefix("/")}"
    }

    override suspend fun userInfo(): UserInfo {
        return httpClient.get("${apiPath}/users/me/full").body()
    }

    override suspend fun plugins(userId: UserId): List<PluginInfoSummary> {
        return httpClient.get("${apiPath}/users/$userId/plugins").body()
    }

    override suspend fun pluginInfo(id: PluginId): PluginInfo {
        return httpClient.get("${apiPath}/plugins/$id").body()
    }

    override suspend fun pluginRating(id: PluginId): PluginRating {
        return httpClient.get("${apiPath}/plugins/$id/rating").body()
    }

    override suspend fun salesInfo(plugin: PluginId): List<PluginSale> {
        return salesInfo(plugin, Marketplace.Birthday.rangeTo(YearMonthDay.now()))
    }

    override suspend fun salesInfo(plugin: PluginId, range: YearMonthDayRange): List<PluginSale> {
        // fetch the sales info year-by-year, because the API only allows a year or less as range
        return range.stepSequence(years = 1)
            .asFlow()
            .map { getSalesInfo(plugin, it) }.toList()
            .flatten()
            .sorted()
    }

    override suspend fun trialsInfo(plugin: PluginId): List<PluginTrial> {
        return trialsInfo(plugin, Marketplace.Birthday.rangeTo(YearMonthDay.now()))
    }

    override suspend fun trialsInfo(plugin: PluginId, range: YearMonthDayRange): List<PluginTrial> {
        return range.stepSequence(years = 1)
            .asFlow()
            .map { getTrialsInfo(plugin, it) }.toList()
            .flatten()
            .sorted()
    }

    override suspend fun downloadsTotal(plugin: PluginId, vararg filters: DownloadFilter): Long {
        val response = httpClient.get("/statistic/downloads-count/plugin") {
            parameter("plugin", plugin)
            addDownloadFilters(filters)
        }.body<DownloadResponse>()

        assert(response.dimension == DownloadDimension.Plugin)
        assert(response.data.dimension == DownloadDimension.Plugin)

        return response.data.serie.single().value
    }

    override suspend fun downloads(
        plugin: PluginId,
        groupType: DownloadDimensionRequest,
        countType: DownloadCountType,
        vararg filters: DownloadFilter
    ): DownloadResponse {
        return httpClient.get("/statistic/${countType.requestPathSegment}/${groupType.requestPathSegment}") {
            parameter("plugin", plugin)
            addDownloadFilters(filters)
        }.body<DownloadResponse>()
    }

    override suspend fun downloadsMonthly(plugin: PluginId, countType: DownloadCountType): List<MonthlyDownload> {
        val response = downloads(plugin, DownloadDimensionRequest.Month, countType)
        assert(response.dimension == DownloadDimension.Month)
        assert(response.data.dimension == DownloadDimension.Month)

        return response.data.serie
            .map { MonthlyDownload(YearMonthDay.parse(it.name), it.value) }
            .sortedBy { it.firstOfMonth }
    }

    override suspend fun downloadsDaily(plugin: PluginId, countType: DownloadCountType): List<DailyDownload> {
        val response = downloads(plugin, DownloadDimensionRequest.Day, countType)
        assert(response.dimension == DownloadDimension.Day)
        assert(response.data.dimension == DownloadDimension.Day)

        return response.data.serie
            .map { DailyDownload(YearMonthDay.parse(it.name), it.value) }
            .sortedBy { it.day }
    }

    override suspend fun downloadsByProduct(plugin: PluginId, countType: DownloadCountType): List<ProductDownload> {
        val response = downloads(plugin, DownloadDimensionRequest.ProductCode, countType)
        assert(response.dimension == DownloadDimension.ProductCode)
        assert(response.data.dimension == DownloadDimension.ProductCode)

        return response.data.serie
            .map { ProductDownload(it.name, it.comment, it.value) }
            .sortedBy { it.productName }
    }

    private suspend fun getSalesInfo(plugin: PluginId, range: YearMonthDayRange): List<PluginSale> {
        return httpClient.get("$apiPath/marketplace/plugin/$plugin/sales-info") {
            parameter("beginDate", range.start.asIsoString)
            parameter("endDate", range.end.asIsoString)
        }.body()
    }

    private suspend fun getTrialsInfo(plugin: PluginId, range: YearMonthDayRange): List<PluginTrial> {
        return httpClient.get("$apiPath/marketplace/plugin/$plugin/trials-info") {
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