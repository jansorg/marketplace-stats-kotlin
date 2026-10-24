/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client

import dev.ja.marketplace.client.model.*
import dev.ja.marketplace.services.CountryWithCurrency
import dev.ja.marketplace.services.ProductRelease
import dev.ja.marketplace.services.ProductWithReleases
import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.nio.file.Files
import java.nio.file.Path

/**
 * Compares the responses of the Marketplace API and of the JetBrains services with the models of the clients.
 * It finds properties, which the API returns, but which the models don't have, e.g. new properties of the API.
 * The client ignores unknown properties, so that new properties don't break it, which would hide them.
 *
 * Only GET requests are made. The test runs only with the environment variables:
 * - `MARKETPLACE_API_KEY`: the API key of the Marketplace, the paid requests need it
 * - `MARKETPLACE_COVERAGE_PLUGIN_IDS`: comma-separated IDs of plugins of the account, paid and free ones
 * - `MARKETPLACE_COVERAGE_REPORT` (optional): file for the report
 *
 * Example: `MARKETPLACE_API_KEY=... MARKETPLACE_COVERAGE_PLUGIN_IDS=1,2 ./gradlew :marketplace-client:test --tests '*LiveApiCoverageTest'`
 */
@EnabledIfEnvironmentVariable(named = "MARKETPLACE_COVERAGE_PLUGIN_IDS", matches = ".+")
class LiveApiCoverageTest {
    private val apiKey = System.getenv("MARKETPLACE_API_KEY")?.takeIf(String::isNotBlank)
    private val plugins = System.getenv("MARKETPLACE_COVERAGE_PLUGIN_IDS").split(",").map { it.trim().toInt() }

    private val marketplace = KtorHttpClientFactory.createHttpClient(Marketplace.HOSTNAME, apiKey, logLevel = ClientLogLevel.None) {
        expectSuccess = true
    }
    private val services = KtorHttpClientFactory.createHttpClient(logLevel = ClientLogLevel.None) {
        expectSuccess = true
    }

    /** The unknown keys of each endpoint with the JSON values, which were found for them. */
    private val unknownKeys = sortedMapOf<String, MutableMap<String, MutableList<JsonElement>>>()
    private val checked = sortedSetOf<String>()
    private val failed = sortedMapOf<String, String>()
    private val decodingErrors = sortedMapOf<String, String>()

    /** Like the JSON configuration of [KtorHttpClientFactory]. */
    private val clientJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    @Test
    fun responsesHaveNoUnknownProperties() = runBlocking {
        check("countries", ListSerializer(CountryWithCurrency.serializer())) {
            services.get("https://account.jetbrains.com/services/countries.json")
        }
        check("products", ListSerializer(ProductWithReleases.serializer())) {
            services.get("https://data.services.jetbrains.com/products")
        }
        check("products/releases", MapSerializer(String.serializer(), ListSerializer(ProductRelease.serializer()))) {
            services.get("https://data.services.jetbrains.com/products/releases") {
                parameter("code", "IIU")
                parameter("type", "release")
            }
        }

        val user = check("users/me/full", UserInfo.serializer()) { marketplace.get("/api/users/me/full") }
        user?.jsonObject?.get("id")?.jsonPrimitive?.content?.let { userId ->
            check("users/{id}/plugins", ListSerializer(PluginInfoSummary.serializer())) {
                marketplace.get("/api/users/$userId/plugins") { parameter("size", 100) }
            }
        }
        check("searchPlugins", MarketplacePluginSearchResponse.serializer()) {
            marketplace.get("/api/searchPlugins") { parameter("max", 20) }
        }

        for (plugin in plugins) {
            checkPlugin(plugin)
        }

        val report = buildString {
            appendLine("Checked: ${checked.joinToString()}")
            if (failed.isNotEmpty()) {
                appendLine("Not available:")
                failed.forEach { (endpoint, error) -> appendLine("  $endpoint: $error") }
            }
            if (decodingErrors.isNotEmpty()) {
                appendLine("Decoding errors:")
                decodingErrors.forEach { (endpoint, error) -> appendLine("  $endpoint: $error") }
            }
            appendLine("Unknown properties:")
            for ((endpoint, keys) in unknownKeys) {
                for ((key, values) in keys) {
                    appendLine("  $endpoint: $key ${describe(values)}")
                }
            }
        }
        System.getenv("MARKETPLACE_COVERAGE_REPORT")?.let { Files.writeString(Path.of(it), report) }
        assertTrue(unknownKeys.isEmpty() && decodingErrors.isEmpty(), report)
    }

    private suspend fun checkPlugin(plugin: PluginId) {
        val api = "/api/plugins/$plugin"
        val marketplaceApi = "/api/marketplace/plugin/$plugin"
        check("plugins/{id}", PluginInfo.serializer()) { marketplace.get(api) }
        check("plugins/{id}/rating", PluginRating.serializer()) { marketplace.get("$api/rating") }
        check("plugins/{id}/compatible-products", ListSerializer(String.serializer())) { marketplace.get("$api/compatible-products") }
        check("plugins/{id}/developers", ListSerializer(JetBrainsAccountInfo.serializer())) { marketplace.get("$api/developers") }
        check("plugins/{id}/channels", ListSerializer(String.serializer())) { marketplace.get("$api/channels") }
        check("plugins/{id}/comments", ListSerializer(PluginReviewComment.serializer())) {
            marketplace.get("$api/comments") { parameter("size", 1000); parameter("page", 1) }
        }
        check("comments/{id}/replies", ListSerializer(PluginReviewComment.serializer())) { marketplace.get("/api/comments/$plugin/replies") }
        for (timeFrame in PageAnalyticsTimeFrame.entries) {
            check("plugins/{id}/ga", PluginPageAnalytics.serializer()) { marketplace.get("$api/ga") { parameter("timeFrame", timeFrame.requestParameter) } }
        }

        val releases = check("plugins/{id}/updates", ListSerializer(PluginReleaseInfo.serializer())) {
            marketplace.get("$api/updates") { parameter("channel", ""); parameter("size", 100); parameter("page", 1) }
        }
        releases?.jsonArray?.take(3)?.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.content }?.forEach { release ->
            check("updates/{id}/dependencies", ListSerializer(PluginDependency.serializer())) { marketplace.get("/api/updates/$release/dependencies") }
            check("products-dependencies/updates/{id}/unsupported", ListSerializer(PluginUnsupportedProduct.serializer())) {
                marketplace.get("/api/products-dependencies/updates/$release/unsupported")
            }
        }

        check("statistic/downloads-count/plugin", DownloadResponse.serializer()) {
            marketplace.get("/statistic/downloads-count/plugin") { parameter("plugin", plugin) }
        }
        for (dimension in DownloadRequestDimension.entries) {
            check("statistic/downloads-unique/{dimension}", DownloadResponse.serializer()) {
                marketplace.get("/statistic/${DownloadCountType.Downloads.requestPathSegment}/${dimension.requestPathSegment}") { parameter("plugin", plugin) }
            }
        }
        check("statistic/downloads/{dimension}/{group}", GroupedDownloadResponse.serializer()) {
            marketplace.get("/statistic/${DownloadCountType.Downloads.requestPathSegment}/${DownloadRequestDimension.Month.requestPathSegment}/${DownloadRequestDimension.ProductCode.requestPathSegment}") {
                parameter("plugin", plugin)
            }
        }

        check("marketplace/plugin/{id}", MarketplacePluginInfo.serializer()) { marketplace.get(marketplaceApi) { parameter("fullInfo", true) } }
        check("marketplace/plugin/{id}/volume-discounts", VolumeDiscountResponse.serializer()) { marketplace.get("$marketplaceApi/volume-discounts") }
        check("marketplace/plugin/{id}/programs", ListSerializer(MarketplaceProgram.serializer())) { marketplace.get("$marketplaceApi/programs") }
        for (country in listOf("US", "DE", "JP")) {
            check("marketplace/plugin/{id}/prices", PluginPriceInfo.serializer()) {
                marketplace.get("$marketplaceApi/prices") { parameter("countryCode", country) }
            }
        }
        check("marketplace/plugin/{id}/release-infos", ListSerializer(PluginReleaseDate.serializer())) { marketplace.get("$marketplaceApi/release-infos") }
        check("plugins/{id}/monetization", PluginMonetization.serializer()) { marketplace.get("$api/monetization") }
        val offers = check("marketplace/plugin/{id}/offers", ListSerializer(PluginOffer.serializer())) { marketplace.get("$marketplaceApi/offers") }
        offers?.jsonArray?.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.content }?.forEach { offer ->
            check("marketplace/plugin/{id}/offers/{offer}", PluginOffer.serializer()) { marketplace.get("$marketplaceApi/offers/$offer") }
            check("marketplace/plugin/{id}/offers/{offer}/coupons", ListSerializer(PluginOfferCoupon.serializer())) {
                marketplace.get("$marketplaceApi/offers/$offer/coupons")
            }
        }

        // the CSV files of the sales reports, the API returns a temporary URL, which is requested without the API key
        for ((report, columns) in listOf("analytics" to SalesReportCsv.analyticsReportColumns, "full-report" to SalesReportCsv.fullReportColumns)) {
            val endpoint = "marketplace/plugin/{id}/$report"
            val csv = try {
                val url = marketplace.get("$marketplaceApi/$report").bodyAsText().trim()
                services.get(url).bodyAsText()
            } catch (e: ResponseException) {
                failed.putIfAbsent(endpoint, "HTTP ${e.response.status.value}")
                continue
            }
            checked += endpoint
            for (column in SalesReportCsv.unknownColumns(csv, columns)) {
                unknownKeys.getOrPut(endpoint, ::sortedMapOf).getOrPut("column $column", ::mutableListOf)
            }
        }

        // the sales and trials of the last two years, in chunks of a month like the client
        val today = YearMonthDay.now()
        for (monthsAgo in 0 until 24) {
            val start = YearMonthDay.firstOfMonth(today.year, today.month).add(0, -monthsAgo, 0)
            val end = YearMonthDay.lastOfMonth(start.year, start.month)
            check("marketplace/plugin/{id}/sales-info", ListSerializer(PluginSale.serializer())) {
                marketplace.get("$marketplaceApi/sales-info") { parameter("beginDate", start.asIsoString); parameter("endDate", end.asIsoString) }
            }
            check("marketplace/plugin/{id}/trials-info", ListSerializer(PluginTrial.serializer())) {
                marketplace.get("$marketplaceApi/trials-info") { parameter("beginDate", start.asIsoString); parameter("endDate", end.asIsoString) }
            }
        }
    }

    /**
     * Requests an endpoint and collects the unknown keys of its response.
     *
     * @return The response, `null` if the endpoint isn't available, e.g. a paid endpoint of a free plugin.
     */
    private suspend fun check(endpoint: String, serializer: KSerializer<*>, request: suspend () -> HttpResponse): JsonElement? {
        val json = try {
            Json.parseToJsonElement(request().bodyAsText())
        } catch (e: ResponseException) {
            failed.putIfAbsent(endpoint, "HTTP ${e.response.status.value}")
            return null
        }
        checked += endpoint
        collectUnknownKeys(endpoint, json, serializer.descriptor)
        // the client must be able to decode the response
        try {
            clientJson.decodeFromJsonElement(serializer, json)
        } catch (e: Exception) {
            decodingErrors.putIfAbsent(endpoint, e.message?.lineSequence()?.firstOrNull() ?: e.toString())
        }
        return json
    }

    private fun collectUnknownKeys(endpoint: String, json: JsonElement, descriptor: SerialDescriptor) {
        for (key in UnknownJsonKeys.find(json, descriptor)) {
            unknownKeys.getOrPut(endpoint, ::sortedMapOf).getOrPut(key, ::mutableListOf) += valuesAt(json, key)
        }
    }

    /** The values of a path of [UnknownJsonKeys.find], e.g. `[].lineItems[].code`. */
    private fun valuesAt(json: JsonElement, path: String): List<JsonElement> {
        var elements = listOf(json)
        for (token in Regex("""\[]|\*|[^.\[\]*]+""").findAll(path).map { it.value }) {
            elements = when (token) {
                "[]" -> elements.flatMap { (it as? JsonArray) ?: emptyList() }
                "*" -> elements.flatMap { (it as? JsonObject)?.values ?: emptyList() }
                else -> elements.mapNotNull { (it as? JsonObject)?.get(token) }
            }
        }
        return elements
    }

    /**
     * The types of the values and, for short values like the values of enums, the distinct values with their number.
     * Long values, e.g. texts and names, are only counted.
     */
    private fun describe(values: List<JsonElement>): String {
        val types = values.groupingBy { value ->
            when (value) {
                JsonNull -> "null"
                is JsonObject -> "object"
                is JsonArray -> "array"
                is JsonPrimitive -> when {
                    value.isString -> "string"
                    value.booleanOrNull != null -> "boolean"
                    value.longOrNull != null -> "integer"
                    else -> "number"
                }
            }
        }.eachCount()
        val enumLike = values.filterIsInstance<JsonPrimitive>().filter { it !is JsonNull }
            .map { it.content }
            .takeIf { contents -> contents.isNotEmpty() && contents.all { it.matches(Regex("[A-Za-z_]{1,24}|true|false")) } }
            ?.groupingBy { it }?.eachCount()
            ?.takeIf { it.size <= 8 }
        return "types=$types" + (enumLike?.let { " values=$it" } ?: "")
    }
}
