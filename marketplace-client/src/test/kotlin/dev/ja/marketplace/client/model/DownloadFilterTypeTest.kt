/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client.model

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DownloadFilterTypeTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun responseWithDateRangeAndUnknownFilters() {
        val response = json.decodeFromString<DownloadResponse>(
            """
            {
                "measure": "downloads-unique",
                "filters": [
                    {"name": "update", "value": "-1"},
                    {"name": "plugin", "value": "1"},
                    {"name": "country", "value": "all"},
                    {"name": "productCode", "value": "IIU"},
                    {"name": "startDate", "value": "2026-01-01"},
                    {"name": "endDate", "value": "2026-03-31"},
                    {"name": "versionMajor", "value": "all"},
                    {"name": "newFilter", "value": "x"}
                ],
                "dim1": "month",
                "data": {"dimension": "plugin", "serie": []}
            }
            """
        )

        assertEquals(
            listOf(
                DownloadFilterType.Update,
                DownloadFilterType.Plugin,
                DownloadFilterType.Country,
                DownloadFilterType.ProductCode,
                DownloadFilterType.StartDate,
                DownloadFilterType.EndDate,
                DownloadFilterType.MajorVersion,
                DownloadFilterType.Unknown,
            ),
            response.filters.map { it.type },
        )
    }

    @Test
    fun encodedWithTheNamesOfTheApi() {
        val filter = DownloadFilter(DownloadFilterType.MajorVersion, "2026")
        val encoded = json.encodeToString(filter)
        assertEquals("""{"name":"versionMajor","value":"2026"}""", encoded)
        assertEquals(filter, json.decodeFromString<DownloadFilter>(encoded))
    }
}
