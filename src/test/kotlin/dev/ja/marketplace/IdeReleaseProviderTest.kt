/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace

import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.data.releases.MajorRelease
import dev.ja.marketplace.services.JetBrainsServiceClient
import dev.ja.marketplace.services.ProductRelease
import dev.ja.marketplace.services.ProductReleaseType
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy

class IdeReleaseProviderTest {
    @Test
    suspend fun releasesBeforeTheMarketplace() {
        // the downloads of free plugins start before the launch of the Marketplace, the releases had other versions then
        val releases = listOf(
            release("2015-11-02", "15.0", "15.0"),
            release("2016-03-17", "2016.1", "2016.1"),
            release("2019-07-24", "2019.2", "2019.2"),
        )
        assertEquals(
            listOf(
                MajorRelease(YearMonthDay(2015, 11, 2), "15.0"),
                MajorRelease(YearMonthDay(2016, 3, 17), "2016.1"),
                MajorRelease(YearMonthDay(2019, 7, 24), "2019.2"),
            ),
            IdeReleaseProvider(servicesClient(releases)).majorReleases(),
        )
    }

    private fun release(date: String, majorVersion: String, version: String) =
        ProductRelease(date = LocalDate.parse(date), type = ProductReleaseType.Release, majorVersion = majorVersion, version = version, patches = emptyMap())

    private fun servicesClient(releases: List<ProductRelease>): JetBrainsServiceClient {
        return Proxy.newProxyInstance(javaClass.classLoader, arrayOf(JetBrainsServiceClient::class.java)) { _, method, _ ->
            when (method.name) {
                "productReleases" -> releases
                else -> throw UnsupportedOperationException(method.name)
            }
        } as JetBrainsServiceClient
    }
}
