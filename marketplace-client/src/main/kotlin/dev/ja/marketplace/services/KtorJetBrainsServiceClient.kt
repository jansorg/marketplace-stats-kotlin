/*
 * Copyright (c) 2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.services

import dev.ja.marketplace.client.ClientLogLevel
import dev.ja.marketplace.client.KtorHttpClientFactory
import io.ktor.client.call.*
import io.ktor.client.request.*

class KtorJetBrainsServiceClient(
    logLevel: ClientLogLevel,
    apiHost: String = "account.jetbrains.com",
) : JetBrainsServiceClient {
    private val apiPath: String = "/services"
    private val httpClient = KtorHttpClientFactory.createHttpClient(apiHost, null, logLevel = logLevel)

    override suspend fun countries(): Countries {
        val countries = httpClient.get("${apiPath}/countries.json").body<List<CountryWithCurrency>>()
        return Countries(countries)
    }
}