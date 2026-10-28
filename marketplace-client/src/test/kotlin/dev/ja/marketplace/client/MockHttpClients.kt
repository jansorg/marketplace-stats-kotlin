/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client

import io.ktor.client.engine.*

/**
 * The settings of the HTTP clients of the application with the [engine] of a test, without retries of failed requests.
 */
fun mockHttpClientBuilder(engine: HttpClientEngine): HttpClientBuilder {
    return HttpClientFactory.builder().withEngine(engine).withRequestRetry(false)
}
