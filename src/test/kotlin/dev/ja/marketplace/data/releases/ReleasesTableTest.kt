/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.releases

import dev.ja.marketplace.client.MarketplaceClient
import dev.ja.marketplace.client.PluginChannel
import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.client.model.PluginReleaseInfo
import dev.ja.marketplace.data.LinkedRelease
import dev.ja.marketplace.data.SimpleDateTableRow
import dev.ja.marketplace.data.format.Formatters
import dev.ja.marketplace.mcp.McpTestData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.math.BigInteger
import kotlin.time.Instant

class ReleasesTableTest {
    private val releases = mapOf(
        "" to listOf(release(1, "1.0", "2025-01-10", 100), release(3, "1.1", "2025-06-01", 50, hidden = true)),
        "eap" to listOf(release(2, "1.1-eap", "2025-05-01", 5, channel = "eap")),
        "nightly" to emptyList(),
    )

    @Test
    suspend fun channels() {
        val byChannel = PluginReleases.byChannel(client(), McpTestData.PLUGIN_ID)
        // the stable channel first, channels without releases are left out
        assertEquals(listOf("", "eap"), byChannel.keys.toList())
        // the newest release first
        assertEquals(listOf("1.1", "1.0"), byChannel[""]!!.map { it.version })
        assertEquals("Stable", PluginReleases.channelTitle(""))
    }

    @Test
    suspend fun tableOfChannel() {
        val factories = ReleasesTableFactories.create(client(), McpTestData.PLUGIN_ID)
        val table = factories.first().createTable(client(), null) as ReleasesTable
        table.init(McpTestData.pluginData())
        val section = table.renderTable().sections.single()

        assertEquals("Channel: Stable", table.title)
        // the releases have no release versions, the state column shows the hidden release
        assertEquals(listOf("release-version", "release-date", "release-downloads", "release-since", "release-until", "release-size", "release-state"), table.columns.map { it.id })

        val rows = section.rows.map { it as SimpleDateTableRow }
        val version = table.columns.first()
        assertEquals(LinkedRelease(3, "1.1", McpTestData.PLUGIN_ID), rows[0].values[version])
        assertEquals(YearMonthDay(2025, 6, 1), rows[0].values[table.columns[1]])
        assertEquals("Hidden", rows[0].values[table.columns.last()])
        assertNull(rows[1].values[table.columns.last()])
        assertEquals("${Formatters.formatDecimal(1.0)} KB", rows[1].values[table.columns[5]])

        val footer = section.footer!!.rows.single() as SimpleDateTableRow
        assertEquals(BigInteger.valueOf(150), footer.values[table.columns[2]])
    }

    private fun release(id: Int, version: String, date: String, downloads: Int, channel: PluginChannel = "", hidden: Boolean = false): PluginReleaseInfo {
        return PluginReleaseInfo(
            id = id,
            pluginId = McpTestData.PLUGIN_ID,
            updateInfoUrlPath = "/plugin/${McpTestData.PLUGIN_ID}/update/$id",
            version = version,
            approve = true,
            listed = true,
            hidden = hidden,
            createdTimestamp = Instant.parse("${date}T12:00:00Z"),
            fileUrlPath = "files/test-$version.zip",
            fileSizeBytes = 1024,
            channel = channel,
            downloads = downloads,
        )
    }

    private fun client(): MarketplaceClient {
        return Proxy.newProxyInstance(javaClass.classLoader, arrayOf(MarketplaceClient::class.java)) { _, method, args ->
            when (method.name) {
                // the API leaves out the stable channel
                "channels" -> listOf("nightly", "eap")
                "releases" -> releases[args[1]]
                else -> throw UnsupportedOperationException(method.name)
            }
        } as MarketplaceClient
    }
}
