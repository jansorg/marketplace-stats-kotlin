/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace

import dev.ja.marketplace.client.*
import dev.ja.marketplace.client.model.*
import dev.ja.marketplace.data.*
import dev.ja.marketplace.data.releases.PluginReleases
import dev.ja.marketplace.mcp.McpReleasesJson
import dev.ja.marketplace.services.JetBrainsServiceClient
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
    val states: List<String>,
    val marketplaceUrl: String,
    val downloadUrl: String,
    /** The release notes as text. */
    val notes: String?,
    val tables: List<RenderedDataTable>,
    val unavailable: List<String>,
) {
    companion object {
        /** The prefix of the names of the optional dependencies of the API. */
        private const val OPTIONAL_PREFIX = "(optional) "

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
            val byMonth = async {
                optional { client.downloads(pluginId, DownloadRequestDimension.Month, DownloadCountType.Downloads, null, DownloadFilter.update(releaseId)) }
            }

            val links = ProductLinks(products.await())
            val tables = listOfNotNull(
                compatibleProductsTable(release, links),
                dependencies.await().orUnavailable(unavailable, "the dependencies")?.let(::dependenciesTable),
                unsupported.await().orUnavailable(unavailable, "the unsupported products")?.let { unsupportedProductsTable(it, links) },
                compatibility.await().orUnavailable(unavailable, "the results of the Plugin Verifier")
                    ?.let { verificationTable("Plugin Verifier", "release-verification-compatibility", it, links) },
                performance.await().orUnavailable(unavailable, "the results of the IDE performance verifier")
                    ?.let { verificationTable("IDE Performance", "release-verification-performance", it, links) },
                byProduct.await().orUnavailable(unavailable, "the downloads by product")?.let { downloadsByProductTable(it, links) },
                byMonth.await().orUnavailable(unavailable, "the downloads by month")?.let(::downloadsByMonthTable),
            ).map { it.renderTable() }.filterNot(RenderedDataTable::isEmpty)

            ReleaseDetails(
                release,
                PluginReleases.channelTitle(channel),
                YearMonthDay.of(release.createdTimestamp),
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
                dependencies.sortedWith(compareBy({ it.dependencyName.startsWith(OPTIONAL_PREFIX) }, { it.dependencyName.removePrefix(OPTIONAL_PREFIX) })).map { dependency ->
                    val info = dependency.plugin
                    mapOf(
                        name to dependency.dependencyName,
                        plugin to info?.let { ExternalLink(it.name, it.link?.let(::marketplaceUrl) ?: marketplaceUrl("/plugin/${it.id}")) },
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
                    mapOf(product to links.of(it.productName, it.productName), dependencies to ItemList(it.dependencies.sorted()))
                },
                // the lists of the missing dependencies are long
                cssClass = "table-column-wide",
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
                        report to it.fullVerificationResultUrl?.let { url -> ExternalLink("Report", marketplaceUrl(url)) },
                    )
                },
                // the verdicts are long texts, the latest results are shown until they're expanded
                cssClass = "table-column-wide",
                collapsedRowCount = 5,
            )
        }

        private fun downloadsByProductTable(response: DownloadResponse, links: ProductLinks): SimpleDataTable {
            val product = DataTableColumn("product", null, accessibleTitle = "Product")
            val downloads = DataTableColumn("downloads", "Downloads", "num", preSorted = AriaSortOrder.Descending)
            val items = response.data.serie.filter { it.value > 0 }.sortedByDescending { it.value }
            return ListTable(
                "Downloads by Product", "release-downloads-products", listOf(product, downloads),
                items.map { mapOf(product to links.of(it.comment ?: it.name, it.name, it.comment), downloads to it.value.toBigInteger()) },
                sortValues = { row -> mapOf(downloads to (row[downloads] as java.math.BigInteger).toLong()) },
                footer = mapOf(downloads to items.sumOf { it.value }.toBigInteger()),
                cssClass = "sortable",
            )
        }

        private fun downloadsByMonthTable(response: DownloadResponse): SimpleDataTable {
            val month = DataTableColumn("month", null, accessibleTitle = "Month")
            val downloads = DataTableColumn("downloads", "Downloads", "num")
            val items = response.data.serie.filter { it.value > 0 }.sortedByDescending { it.name }
            return ListTable(
                "Downloads by Month", "release-downloads-months", listOf(month, downloads),
                // the names are the first days of the months, e.g. 2026-03-01
                items.map { mapOf(month to it.name.take(7), downloads to it.value.toBigInteger()) },
                footer = mapOf(downloads to items.sumOf { it.value }.toBigInteger()),
                collapsedRowCount = 12,
            )
        }
    }

    /**
     * The links to the pages of the products of JetBrains by their codes, names and alternative codes, e.g. `IU`,
     * `IntelliJ IDEA` or `IIU`, case-insensitive.
     */
    private class ProductLinks(products: Products?) {
        private val links: Map<String, String> = products?.productsWithReleases.orEmpty().flatMap { product ->
            val link = product.link ?: return@flatMap emptyList()
            (listOfNotNull(product.code.code, product.name, product.intellijProductCode, product.salesCode) + product.alternativeCodes.orEmpty())
                .map { it.lowercase() to link }
        }.toMap()

        /** @return The [text] linked to the page of the first product of the [keys], or the text without a known product. */
        fun of(text: String, vararg keys: String?): Any {
            val link = keys.firstNotNullOfOrNull { key -> key?.let { links[it.lowercase()] } } ?: return text
            return ExternalLink(text, link)
        }
    }

    /** A table of values, which are known when it's created. */
    private class ListTable(
        title: String,
        id: String,
        override val columns: List<DataTableColumn>,
        private val rows: List<Map<DataTableColumn, Any?>>,
        private val sortValues: (Map<DataTableColumn, Any?>) -> Map<DataTableColumn, Long?> = { emptyMap() },
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
                    rows.map { SimpleDateTableRow(values = it, sortValues = sortValues(it)) },
                    footer = footer?.let { SimpleRowGroup(listOf(SimpleDateTableRow(values = it))) },
                )
            )
        }
    }
}
