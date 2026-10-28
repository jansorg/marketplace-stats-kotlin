/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace

import com.github.ajalt.clikt.testing.test
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ApplicationHelpTest {
    @Test
    fun helpShowsTheDefaultRateLimit() {
        val help = Application("1.0").test("--help").output
        // the help of an option ends with its default, the lines may be wrapped
        val rateLimitHelp = help.substringAfter("--rate-limit").substringBefore("--events")
        assertTrue(Regex("""default:\s+10\b""").containsMatchIn(rateLimitHelp), help)
    }
}
