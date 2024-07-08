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
        bearerAuthKey: String?,
        logLevel: ClientLogLevel = ClientLogLevel.Normal
    ): HttpClient {
        return HttpClient(Java) {
            install(Logging) {
                level = logLevel.ktorLogLevel
            }
            install(Resources)
            install(HttpCache)
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