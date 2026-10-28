/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client

/**
 * Creates the HTTP clients of the Marketplace and the JetBrains services.
 */
object HttpClientFactory {
    /** The `User-Agent` of the requests, if the builder has no other agent. */
    const val DEFAULT_USER_AGENT: String = "marketplace-stats"

    /**
     * @return A builder with the default settings: without a base URL and authentication, without logging, with
     *  retries of failed requests and the user agent [DEFAULT_USER_AGENT].
     */
    fun builder(): HttpClientBuilder = HttpClientBuilder()
}
