/*
 * Copyright (c) 2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client

import io.ktor.client.*
import io.ktor.client.engine.java.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.cache.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.plugins.resources.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json

object KtorHttpClientFactory {
    fun createHttpClient(
        apiHost: String,
        bearerAuthKey: String? = null,
        apiProtocol: URLProtocol = URLProtocol.HTTPS,
        apiPort: Int = apiProtocol.defaultPort,
        logLevel: ClientLogLevel = ClientLogLevel.Normal,
        enableHttpCaching: Boolean = true,
    ): HttpClient {
        val url = URLBuilder()
        url.protocol = apiProtocol
        url.host = apiHost
        url.port = apiPort

        return createHttpClientByUrl(url.buildString(), bearerAuthKey, logLevel, enableHttpCaching)
    }

    fun createHttpClientByUrl(
        apiUrl: String,
        bearerAuthKey: String?,
        logLevel: ClientLogLevel,
        enableHttpCaching: Boolean,
    ): HttpClient {
        return HttpClient(Java) {
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

            if (enableHttpCaching) {
                install(HttpCache)
            }

            install(DefaultRequest) {
                url(apiUrl)

                header("Content-Type", "application/json")

                if (bearerAuthKey != null) {
                    bearerAuth(bearerAuthKey)
                }
            }

            expectSuccess = true
            engine {
                pipelining = true
            }
        }

    }
}