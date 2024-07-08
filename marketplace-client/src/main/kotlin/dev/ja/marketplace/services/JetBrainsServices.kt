/*
 * Copyright (c) 2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.services

import dev.ja.marketplace.client.ClientLogLevel
import dev.ja.marketplace.client.KtorHttpClientFactory
import io.ktor.client.call.*
import io.ktor.client.request.*

class JetBrainsServices(
    apiHost: String = "account.jetbrains.com",
    logLevel: ClientLogLevel = ClientLogLevel.None,
) {
    private val apiPath: String = "/services"
    private val httpClient = KtorHttpClientFactory.createHttpClient(apiHost, null, logLevel)

    suspend fun countries(): Countries {
        val countries = httpClient.get("${apiPath}/countries.json").body<List<CountryWithCurrency>>()
        return Countries(countries)
    }
}