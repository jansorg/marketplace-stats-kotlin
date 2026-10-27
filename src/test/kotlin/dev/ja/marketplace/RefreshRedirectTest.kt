/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace

import io.ktor.http.Url
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RefreshRedirectTest {
    @Test
    fun refererOfTheRequestedServer() {
        // a server bound to 0.0.0.0, which is requested at localhost
        val referer = Url("http://localhost:8081/customers?pluginId=1&discount=20%25+continuity+discount&rows=500")
        assertTrue(isPageOfRequestedServer(referer, "localhost", 8081))
        assertTrue(isPageOfRequestedServer(referer, "LOCALHOST", 8081))
        assertFalse(isPageOfRequestedServer(referer, "localhost", 8080))
        assertFalse(isPageOfRequestedServer(referer, "example.com", 8081))
        // the default port of the scheme
        assertTrue(isPageOfRequestedServer(Url("https://stats.example.com/licenses?pluginId=1"), "stats.example.com", 443))
    }
}
