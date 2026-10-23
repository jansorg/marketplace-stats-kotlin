/*
 * Copyright (c) 2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.services

import dev.ja.marketplace.client.ClientLogLevel
import dev.ja.marketplace.client.KtorHttpClientFactory
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*

class KtorJetBrainsServiceClient(
    private val accountServicesUrl: String = "https://account.jetbrains.com",
    private val dataServicesUrl: String = "https://data.services.jetbrains.com",
    private val httpClient: HttpClient
) : JetBrainsServiceClient {
    constructor(
        accountServicesUrl: String = "https://account.jetbrains.com",
        dataServicesUrl: String = "https://data.services.jetbrains.com",
        logLevel: ClientLogLevel = ClientLogLevel.Normal,
    ) : this(accountServicesUrl, dataServicesUrl, KtorHttpClientFactory.createHttpClient(logLevel = logLevel))

    override suspend fun countries(): Countries {
        val url = URLBuilder(accountServicesUrl).also {
            it.encodedPath = "/services/countries.json"
        }
        val countries = httpClient.get(url.build()).body<List<CountryWithCurrency>>()
        return Countries(countries)
    }

    override suspend fun products(): Products {
        val url = URLBuilder(dataServicesUrl).also {
            it.encodedPath = "/products"
        }
        val productsData = httpClient.get(url.build()).body<List<ProductWithReleases>>()
        return Products(productsData)
    }

    override suspend fun productReleases(product: JetBrainsProductCode, type: ProductReleaseType): List<ProductRelease> {
        val url = URLBuilder(dataServicesUrl).also {
            it.encodedPath = "/products/releases"
            it.parameters.append("code", product.code)
            it.parameters.append("type", type.jsonId)
        }
        // the response maps the product code to its releases
        return httpClient.get(url.build()).body<Map<String, List<ProductRelease>>>()[product.code] ?: emptyList()
    }
}