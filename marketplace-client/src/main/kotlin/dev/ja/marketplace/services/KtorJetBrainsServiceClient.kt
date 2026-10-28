/*
 * Copyright (c) 2024-2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.services

import dev.ja.marketplace.client.HttpClientBuilder
import dev.ja.marketplace.client.HttpClientFactory
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*

/**
 * Client of the services of JetBrains with Ktor.
 *
 * @param httpClientBuilder The settings of the HTTP client, e.g. the log level and the user agent. The client creates
 *  its own HTTP client, which [close] closes.
 */
class KtorJetBrainsServiceClient(
    httpClientBuilder: HttpClientBuilder = HttpClientFactory.builder(),
    private val accountServicesUrl: String = JetBrainsServiceClient.ACCOUNT_SERVICES_URL,
    private val dataServicesUrl: String = JetBrainsServiceClient.DATA_SERVICES_URL,
) : JetBrainsServiceClient {
    // the requests have absolute URLs of the two services, an API key of the Marketplace must not be sent to them
    internal val httpClient: HttpClient = httpClientBuilder.withBaseUrl(null).withBearerAuth(null).build()

    /** Closes the HTTP client and its connections, requests fail afterward. */
    override fun close() {
        httpClient.close()
    }

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