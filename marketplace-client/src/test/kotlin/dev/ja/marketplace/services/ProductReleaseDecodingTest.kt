/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.services

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ProductReleaseDecodingTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun unknownPatchPlatformsAndMissingPatches() {
        val releases = json.decodeFromString<List<ProductRelease>>(
            """
            [
              {"date": "2025-04-15", "type": "release", "majorVersion": "2025.1", "version": "2025.1", "build": "251.23774.435",
               "patches": {"unix": [], "linuxARM64": [{"fromBuild": "243.1", "link": "https://example.com/patch", "size": 100, "checksumLink": "https://example.com/sum"}]}},
              {"date": "2025-04-16", "type": "release", "majorVersion": "2025.1", "version": "2025.1.1", "build": "251.SNAPSHOT"}
            ]
            """.trimIndent()
        )
        assertEquals(setOf("unix", "linuxARM64"), releases[0].patches.keys)
        assertEquals(emptyMap<String, List<ProductPatchRelease>>(), releases[1].patches)
        assertEquals(BuildNumber.of("251"), releases[1].build)
    }
}
