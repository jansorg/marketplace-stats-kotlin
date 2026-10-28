/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.releases

import dev.ja.marketplace.client.*
import dev.ja.marketplace.client.model.PluginReleaseInfo
import dev.ja.marketplace.data.*
import dev.ja.marketplace.data.chart.ChartMetricFamily
import dev.ja.marketplace.data.chart.ChartUnit
import dev.ja.marketplace.data.format.Formatters
import dev.ja.marketplace.client.model.PluginDependency
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** The releases of a plugin by their channel. */
object PluginReleases {

    /**
     * @return The releases of each channel of the plugin, newest first. The stable channel comes first, the other
     *  channels are sorted by their name. Channels without releases are left out.
     */
    suspend fun byChannel(client: MarketplaceClient, pluginId: PluginId): Map<PluginChannel, List<PluginReleaseInfo>> = coroutineScope {
        // the stable channel is the empty name, the API may leave it out
        val channels = (listOf(PluginChannelNames.Stable) + client.channels(pluginId).sorted()).distinct()
        channels
            .map { channel -> async { channel to client.releases(pluginId, channel, pageSize = 100) } }
            .awaitAll()
            .filter { (_, releases) -> releases.isNotEmpty() }
            .associate { (channel, releases) -> channel to releases.sortedByDescending { it.createdTimestamp } }
    }

    /** @return The title of a channel, e.g. "Stable" or "eap". */
    fun channelTitle(channel: PluginChannel): String = channel.ifEmpty { "Stable" }

    /** @return The title of the table of the releases of a channel, e.g. "Channel: Stable". */
    fun tableTitle(channel: PluginChannel): String = "Channel: ${channelTitle(channel)}"

    /**
     * @return The plugin model of a release by its dependencies, `v1` or `v2`: the plugin model v2 declares
     *  dependencies on modules of the platform, e.g. `intellij.platform.vcs`, the plugin model v1 depends on plugins
     *  and on the modules of the products, e.g. `com.intellij.modules.platform`. It's a heuristic, the API has no
     *  plugin model.
     */
    fun pluginModel(dependencies: List<PluginDependency>): String {
        return if (dependencies.any { it.name.startsWith("intellij.") }) "v2" else "v1"
    }

    /** @return The tooltip of a plugin model, e.g. "Plugin model v2". */
    fun pluginModelTooltip(model: String): String = "Plugin model $model"

    /** @return The states of a release, which aren't the usual ones, e.g. "Hidden". */
    fun unusualStates(release: PluginReleaseInfo): List<String> {
        return listOfNotNull(
            "Hidden".takeIf { release.hidden },
            "Unlisted".takeIf { !release.listed },
            "Not approved".takeIf { !release.approve },
        )
    }
}

/** Creates a table of the releases of each channel of a plugin, see [ReleasesTable]. */
object ReleasesTableFactories {
    suspend fun create(client: MarketplaceClient, pluginId: PluginId): List<MarketplaceDataTableFactory> {
        return PluginReleases.byChannel(client, pluginId).map { (channel, releases) ->
            object : MarketplaceDataTableFactory {
                override fun createTable(client: MarketplaceClient, maxTableRows: Int?): DataTable = ReleasesTable(channel, releases)
            }
        }
    }
}

/**
 * The releases of a channel of a plugin, newest first: the version links to the page of the release.
 */
class ReleasesTable(
    channel: PluginChannel,
    private val releases: List<PluginReleaseInfo>,
) : SimpleDataTable(PluginReleases.tableTitle(channel), "releases-${channel.ifEmpty { "stable" }}", "table-column-wide sortable"),
    MarketplaceDataSink {
    private var pluginId: PluginId? = null

    /** Only paid and freemium plugins have a release version, e.g. `20261`. */
    private var isPaidOrFreemium: Boolean = false

    // the header of the column is the title of the table, the name of the channel
    private val columnVersion = DataTableColumn("release-version", null, accessibleTitle = "Version")
    private val columnDate = DataTableColumn("release-date", "Published", preSorted = AriaSortOrder.Descending)
    private val columnDownloads = DataTableColumn("release-downloads", "Downloads", "num")
    private val columnSince = DataTableColumn("release-since", "From", tooltip = "The first build of the IDEs, which the release supports")
    private val columnUntil = DataTableColumn("release-until", "Until", tooltip = "The last build of the IDEs, which the release supports, e.g. 252.*")
    private val columnSize = DataTableColumn("release-size", "Size", "num")
    private val columnReleaseVersion = DataTableColumn(
        "release-release-version", "Release Version",
        tooltip = "The release version of a paid or freemium plugin, which the licenses of a version cover",
    )
    private val columnState = DataTableColumn("release-state", "State", tooltip = "Releases, which are hidden, unlisted or not approved")


    override val columns: List<DataTableColumn> by lazy {
        listOfNotNull(
            columnVersion,
            columnDate,
            columnDownloads,
            columnSince,
            columnUntil,
            columnSize,
            columnReleaseVersion.takeIf { isPaidOrFreemium && releases.any { it.releaseVersion != null } },
            columnState.takeIf { releases.any { PluginReleases.unusualStates(it).isNotEmpty() } },
        )
    }

    // the latest releases, the others are shown on demand
    override val collapsedRowCount: Int = SHOWN_ROWS

    override suspend fun init(data: PluginData) {
        super.init(data)
        this.pluginId = data.pluginId
        this.isPaidOrFreemium = data.pluginSummary.isPaidOrFreemium
    }

    override suspend fun process(licenseInfo: LicenseInfo) {
        // ignored
    }

    override suspend fun createSections(): List<DataTableSection> {
        val pluginId = pluginId!!
        // the titles of the charts of the downloads of the releases contain their versions
        releases.forEach { ChartMetricFamily.ReleaseDownloads.parameters[0].registerLabel(it.id.toString(), it.version) }
        val rows = releases.map { release ->
            val published = release.createdTimestamp.epochSeconds
            SimpleDateTableRow(
                values = mapOf(
                    columnVersion to LinkedRelease(release.id, release.version, pluginId),
                    columnDate to YearMonthDay.of(release.createdTimestamp),
                    // a click shows the downloads of the release in a chart, see ChartMetricFamily.ReleaseDownloads
                    // the downloads of a long time range take long to load, the chart shows the current year
                    columnDownloads to ChartMetricName(
                        Formatters.formatInteger(release.downloads), downloadsMetric(release).id, "Downloads of ${release.version}", ChartUnit.Count.id(""),
                        ChartMetricName.THIS_YEAR,
                    ),
                    columnSince to release.since?.toString(),
                    columnUntil to release.until?.toString(),
                    columnSize to Formatters.formatFileSize(release.fileSizeBytes),
                    columnReleaseVersion to release.releaseVersion,
                    columnState to PluginReleases.unusualStates(release).joinToString(", ").ifEmpty { null },
                ),
                // versions are sorted by their date, their texts don't sort like versions, e.g. 1.10 and 1.9
                sortValues = mapOf(
                    columnVersion to published,
                    columnDate to published,
                    columnDownloads to release.downloads.toLong(),
                    // e.g. 252.* after 252.123, a release without an end after all others
                    columnSince to (release.since?.sortValue ?: 0),
                    columnUntil to (release.until?.sortValue ?: Long.MAX_VALUE),
                    columnSize to release.fileSizeBytes,
                ),
            )
        }

        val downloads = releases.sumOf { it.downloads.toLong() }
        val footer = SimpleDateTableRow(
            values = mapOf(
                columnVersion to "${Formatters.formatInteger(releases.size)} ${if (releases.size == 1) "release" else "releases"}",
                columnDownloads to downloads.toBigInteger(),
            ),
        )
        return listOf(SimpleTableSection(rows, footer = SimpleRowGroup(footer)))
    }

    private fun downloadsMetric(release: PluginReleaseInfo) = ChartMetricFamily.ReleaseDownloads.metric(listOf(release.id.toString(), null))

    companion object {
        /** The latest releases, which are shown until the table is expanded. */
        const val SHOWN_ROWS = 7
    }
}
