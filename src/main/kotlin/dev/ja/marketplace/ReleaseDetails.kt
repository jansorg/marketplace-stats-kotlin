/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace

import dev.ja.marketplace.client.*
import dev.ja.marketplace.client.model.*
import dev.ja.marketplace.data.*
import dev.ja.marketplace.data.chart.ChartMetric
import dev.ja.marketplace.data.chart.ChartMetricFamily
import dev.ja.marketplace.data.chart.ChartUnit
import dev.ja.marketplace.data.format.Formatters
import dev.ja.marketplace.data.releases.PluginReleases
import dev.ja.marketplace.mcp.McpReleasesJson
import dev.ja.marketplace.services.JetBrainsServiceClient
import dev.ja.marketplace.services.ProductWithReleases
import dev.ja.marketplace.services.Products
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlin.time.Instant

/**
 * The data of the page of a release of a plugin, see `release.kte`: the properties of the release and the tables of
 * its dependencies, verifications and downloads.
 *
 * @param unavailable The data, which couldn't be loaded, e.g. the results of the verifiers without the API key of a
 *  developer of the plugin.
 */
class ReleaseDetails(
    val release: PluginReleaseInfo,
    val channel: String,
    val published: YearMonthDay,
    /** The badge of the plugin model of the release, e.g. `v2`, `null` if the dependencies are unknown, see [PluginReleases.pluginModel]. */
    val pluginModel: Badge?,
    /** The downloads of the release with a button, which shows them in a chart. */
    val downloads: ChartMetricName,
    val states: List<String>,
    val marketplaceUrl: String,
    val downloadUrl: String,
    /** The release notes as text. */
    val notes: String?,
    val tables: List<RenderedDataTable>,
    val unavailable: List<String>,
) {
    companion object {
        /**
         * @param servicesClient The products of JetBrains, the names of the products link to their pages.
         * @return The details of the release of the plugin, `null` if the plugin has no such release.
         */
        suspend fun load(
            client: MarketplaceClient,
            servicesClient: JetBrainsServiceClient,
            pluginId: PluginId,
            releaseId: PluginReleaseId,
        ): ReleaseDetails? = coroutineScope {
            val (channel, release) = PluginReleases.byChannel(client, pluginId).firstNotNullOfOrNull { (channel, releases) ->
                releases.firstOrNull { it.id == releaseId }?.let { channel to it }
            } ?: return@coroutineScope null

            val unavailable = mutableListOf<String>()
            // the products are optional, the names are shown without links
            val products = async { optional { servicesClient.products() }.getOrNull() }
            val dependencies = async { optional { client.pluginReleaseDependencies(releaseId) } }
            val unsupported = async { optional { client.unsupportedReleaseProducts(releaseId) } }
            val compatibility = async { optional { client.verificationResults(releaseId, PluginVerificationType.IntelliJCompatibility) } }
            val performance = async { optional { client.verificationResults(releaseId, PluginVerificationType.IdePerformance) } }
            val byProduct = async {
                optional { client.downloads(pluginId, DownloadRequestDimension.ProductCode, DownloadCountType.Downloads, null, DownloadFilter.update(releaseId)) }
            }

            // the titles of the charts of the downloads of the release contain its version
            ChartMetricFamily.ReleaseDownloads.parameters[0].registerLabel(release.id.toString(), release.version)
            val links = ProductLinks(products.await())
            val loadedDependencies = dependencies.await().orUnavailable(unavailable, "the dependencies")
            val tables = listOfNotNull(
                byProduct.await().orUnavailable(unavailable, "the downloads by product")?.let { downloadsByProductTable(it, links, release) },
                compatibleProductsTable(release, links),
                loadedDependencies?.let(::dependenciesTable),
                unsupported.await().orUnavailable(unavailable, "the unsupported products")?.let { unsupportedProductsTable(it, links) },
                compatibility.await().orUnavailable(unavailable, "the results of the Plugin Verifier")
                    ?.let { verificationTable("Plugin Verifier", "release-verification-compatibility", it, links) },
                performance.await().orUnavailable(unavailable, "the results of the IDE performance verifier")
                    ?.let { verificationTable("IDE Performance", "release-verification-performance", it, links) },
            ).map { it.renderTable() }.filterNot(RenderedDataTable::isEmpty)

            ReleaseDetails(
                release,
                PluginReleases.channelTitle(channel),
                YearMonthDay.of(release.createdTimestamp),
                loadedDependencies?.let { PluginReleases.pluginModel(it) }?.let { Badge(it, "badge-model", PluginReleases.pluginModelTooltip(it)) },
                downloadsChart(release.downloads.toLong(), ChartMetricFamily.ReleaseDownloads.metric(listOf(release.id.toString(), null))),
                PluginReleases.unusualStates(release),
                release.getUpdateInfoPageUrl().toString(),
                release.getMarketplaceDownloadLink().toString(),
                release.notes?.let(McpReleasesJson::htmlToText)?.ifBlank { null },
                tables,
                unavailable,
            )
        }

        private suspend fun <T> optional(block: suspend () -> T): Result<T> {
            return try {
                Result.success(block())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

        private fun <T> Result<T>.orUnavailable(unavailable: MutableList<String>, name: String): T? {
            return getOrElse {
                unavailable += name
                null
            }
        }

        /** @return The number of downloads with a button, which shows the downloads of the metric in a chart. */
        private fun downloadsChart(downloads: Long, metric: ChartMetric): ChartMetricName {
            return ChartMetricName(Formatters.formatInteger(downloads), metric.id, metric.title, ChartUnit.Count.id(""))
        }

        private fun marketplaceUrl(path: String): String = URLBuilder(Marketplace.MarketplaceFrontendUrl).takeFrom(path).buildString()

        private fun compatibleProductsTable(release: PluginReleaseInfo, links: ProductLinks): SimpleDataTable? {
            val versions = release.compatibleVersions?.takeIf { it.isNotEmpty() } ?: return null
            val product = DataTableColumn("product", null, accessibleTitle = "Product")
            val range = DataTableColumn("versions", "Versions")
            return ListTable(
                "Compatible Products", "release-compatible-products", listOf(product, range),
                versions.entries.sortedBy { it.key }.map { (id, versionRange) -> mapOf(product to links.of(id, id), range to versionRange) },
            )
        }

        private fun dependenciesTable(dependencies: List<PluginDependency>): SimpleDataTable {
            val name = DataTableColumn("dependency", null, accessibleTitle = "Dependency")
            val plugin = DataTableColumn("plugin", "Plugin")
            return ListTable(
                "Dependencies", "release-dependencies", listOf(name, plugin),
                // the optional dependencies follow the required ones
                dependencies.sortedWith(compareBy({ it.isOptional }, { it.name })).map { dependency ->
                    val info = dependency.plugin
                    mapOf(
                        name to dependency.dependencyName,
                        plugin to info?.let {
                            ExternalLink(it.name, it.link?.let(::marketplaceUrl) ?: marketplaceUrl("/plugin/${it.id}"), "Open the page of ${it.name} on the JetBrains Marketplace")
                        },
                    )
                },
            )
        }

        private fun unsupportedProductsTable(products: List<PluginUnsupportedProduct>, links: ProductLinks): SimpleDataTable {
            val product = DataTableColumn("product", null, accessibleTitle = "Product")
            val dependencies = DataTableColumn("dependencies", "Missing Dependencies")
            return ListTable(
                "Unsupported Products", "release-unsupported-products", listOf(product, dependencies),
                products.sortedBy { it.productName }.map {
                    mapOf(product to links.of(it.productName, it.productName), dependencies to it.dependencies.sorted().joinToString(", "))
                },
                // the lists of the missing dependencies are long
                cssClass = "table-column-wide",
                collapsedRowCount = 2,
            )
        }

        private fun verificationTable(title: String, id: String, results: List<PluginVerificationResult>, links: ProductLinks): SimpleDataTable {
            val ide = DataTableColumn("ide", null, accessibleTitle = "IDE")
            val result = DataTableColumn("result", "Result")
            val verdict = DataTableColumn("verdict", "Verdict")
            // a wrapped date looks odd
            val date = DataTableColumn("date", "Verified", "nowrap")
            val report = DataTableColumn("report", "Report")
            return ListTable(
                title, id, listOf(ide, result, verdict, date, report),
                results.sortedByDescending { it.verificationDateMillis ?: 0 }.map {
                    val product = it.product
                    mapOf(
                        ide to links.of(
                            listOfNotNull(product?.productName ?: product?.product, product?.shortPresentableVersion ?: product?.ideVersion).joinToString(" "),
                            product?.product, product?.productName,
                        ),
                        result to it.resultType,
                        verdict to it.verificationVerdict,
                        date to it.verificationDateMillis?.let { millis -> YearMonthDay.of(Instant.fromEpochMilliseconds(millis)) },
                        // the reports of the Plugin Verifier have a path of the Marketplace, those of the performance an address
                        report to it.fullVerificationResultUrl?.let { url -> ExternalLink("Report", marketplaceUrl(url), "Open the full report of the verification") },
                    )
                },
                // the verdicts are long texts, the latest results are shown until they're expanded
                cssClass = "table-column-wide",
                collapsedRowCount = 5,
            )
        }

        /** The downloads have a button, which shows the downloads of the release in the product in a chart. */
        private fun downloadsByProductTable(response: DownloadResponse, links: ProductLinks, release: PluginReleaseInfo): SimpleDataTable {
            val product = DataTableColumn("product", null, accessibleTitle = "Product")
            val downloads = DataTableColumn("downloads", "Downloads", "num", preSorted = AriaSortOrder.Descending)
            val items = response.data.serie.filter { it.value > 0 }.sortedByDescending { it.value }
            val family = ChartMetricFamily.ReleaseDownloads
            return ListTable(
                "Downloads by Product", "release-downloads-products", listOf(product, downloads),
                items.map { item ->
                    family.parameters[1].registerLabel(item.name, item.productLabel)
                    val chart = family.parse("${family.id}.${release.id}.${item.name}")
                    mapOf(
                        product to links.of(item.productLabel, item.name, item.comment),
                        downloads to (chart?.let { downloadsChart(item.value, it) } ?: Formatters.formatInteger(item.value)),
                    )
                },
                sortValues = { index -> mapOf(downloads to items[index].value) },
                footer = mapOf(downloads to items.sumOf { it.value }.toBigInteger()),
                cssClass = "sortable",
            )
        }
    }

    /**
     * The links to the pages of the products of JetBrains by their codes, names and alternative codes, e.g. `IIU` or
     * `IntelliJ IDEA`, case-insensitive.
     */
    private class ProductLinks(products: Products?) {
        private val products: Map<String, ProductWithReleases> = products?.productsWithReleases.orEmpty().filter { it.link != null }.flatMap { product ->
            (listOfNotNull(product.code.code, product.name, product.intellijProductCode, product.salesCode) + product.alternativeCodes.orEmpty())
                .map { it.lowercase() to product }
        }.toMap()

        /**
         * @return The [text] linked to the page of the first product of the [keys], with the name of the product as
         *  tooltip, or the text without a known product.
         */
        fun of(text: String, vararg keys: String?): Any {
            val product = keys.firstNotNullOfOrNull { key -> key?.let { products[it.lowercase()] } } ?: return text
            return ExternalLink(text, product.link!!, "Open the page of ${product.name}")
        }
    }

    /** A table of values, which are known when it's created. */
    private class ListTable(
        title: String,
        id: String,
        override val columns: List<DataTableColumn>,
        private val rows: List<Map<DataTableColumn, Any?>>,
        /** The values of the sortable table by the index of a row, e.g. of formatted numbers. */
        private val sortValues: (Int) -> Map<DataTableColumn, Long?> = { emptyMap() },
        private val footer: Map<DataTableColumn, Any?>? = null,
        cssClass: String? = null,
        override val collapsedRowCount: Int? = null,
    ) : SimpleDataTable(title, id, cssClass) {
        override suspend fun process(licenseInfo: LicenseInfo) {
            // the values are known
        }

        override suspend fun createSections(): List<DataTableSection> {
            return listOf(
                SimpleTableSection(
                    rows.mapIndexed { index, row -> SimpleDateTableRow(values = row, sortValues = sortValues(index)) },
                    footer = footer?.let { SimpleRowGroup(listOf(SimpleDateTableRow(values = it))) },
                )
            )
        }
    }
}
