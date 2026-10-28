/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.mcp

import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.client.model.PluginReleaseInfo
import dev.ja.marketplace.client.model.PluginVerificationResult
import dev.ja.marketplace.data.prices.PriceChange
import dev.ja.marketplace.data.releases.MajorRelease
import dev.ja.marketplace.services.ProductRelease
import dev.ja.marketplace.services.ProductWithReleases
import kotlinx.datetime.toJavaLocalDate
import kotlinx.serialization.json.*
import java.math.RoundingMode
import kotlin.time.Instant

/**
 * Converts releases of plugins and JetBrains products, and price changes into JSON for the MCP tools.
 */
object McpReleasesJson {
    fun pluginRelease(release: PluginReleaseInfo): JsonObject {
        return buildJsonObject {
            put("id", release.id)
            put("version", release.version)
            put("date", YearMonthDay.of(release.createdTimestamp).asIsoString)
            put("channel", release.channel.ifEmpty { "stable" })
            put("downloads", release.downloads)
            put("sinceBuild", release.since?.toString())
            put("untilBuild", release.until?.toString())
            // only unusual states, most releases are listed, visible and approved
            if (!release.listed) put("listed", false)
            if (release.hidden) put("hidden", true)
            if (!release.approve) put("approved", false)
        }
    }

    /**
     * The result of a verifier for a single IDE, without the internal properties of the Marketplace.
     */
    fun verificationResult(result: PluginVerificationResult): JsonObject {
        return buildJsonObject {
            result.product?.let { product ->
                put("product", product.product)
                product.productName?.let { put("productName", it) }
                (product.fullPresentableVersion ?: product.ideVersion)?.let { put("ideVersion", it) }
            }
            put("resultType", result.resultType)
            result.verificationVerdict?.let { put("verdict", it) }
            result.verificationDateMillis?.let { put("date", YearMonthDay.of(Instant.fromEpochMilliseconds(it)).asIsoString) }
            result.fullVerificationResultUrl?.let { put("reportUrl", it) }
        }
    }

    fun productRelease(release: ProductRelease): JsonObject {
        return buildJsonObject {
            put("version", release.version)
            put("majorVersion", release.majorVersion)
            put("build", release.build?.toBuilderNumberString())
            put("date", YearMonthDay.of(release.date.toJavaLocalDate()).asIsoString)
            put("type", release.type.jsonId)
            put("notesLink", release.notesLink)
        }
    }

    /**
     * All public metadata of a release, including the downloads and patches.
     */
    fun productReleaseDetails(release: ProductRelease, includeWhatsNew: Boolean, includeDownloads: Boolean): JsonObject {
        return buildJsonObject {
            productRelease(release).forEach { (key, value) -> put(key, value) }
            put("printableReleaseType", release.printableReleaseType)
            put("licenseRequired", release.licenseRequired)
            put("isSecurityCritical", release.isSecurityCritical)
            if (includeWhatsNew) {
                put("whatsNew", release.whatsNewHtml?.let(::htmlToText))
            }
            if (!includeDownloads) {
                return@buildJsonObject
            }
            put("downloads", buildJsonArray {
                for ((type, download) in release.downloads) {
                    add(buildJsonObject {
                        put("platform", type.id)
                        put("name", type.displayName)
                        put("link", download.link)
                        put("checksumLink", download.checksumUrl)
                        put("sizeBytes", download.fileSizeBytes)
                    })
                }
            })
            put("patches", buildJsonArray {
                for ((type, patches) in release.patches) {
                    for (patch in patches) {
                        add(buildJsonObject {
                            put("platform", type)
                            put("fromBuild", patch.fromBuild)
                            put("link", patch.downloadLink)
                            put("checksumLink", patch.checksumLink)
                            put("sizeBytes", patch.downloadFileSizeBytes)
                        })
                    }
                }
            })
        }
    }

    fun product(product: ProductWithReleases, includeDetails: Boolean): JsonObject {
        val latestRelease = product.releases.maxByOrNull { it.date }
        return buildJsonObject {
            put("code", product.code.code)
            put("name", product.name)
            product.productFamilyName?.let { put("productFamilyName", it) }
            put("forSale", product.forSale)
            product.salesCode?.let { put("salesCode", it) }
            product.intellijProductCode?.let { put("intellijProductCode", it) }
            product.alternativeCodes?.takeIf { it.isNotEmpty() }?.let { codes -> put("alternativeCodes", buildJsonArray { codes.forEach { add(it) } }) }
            put("categories", buildJsonArray { product.categories?.forEach { add(it.id) } })
            if (includeDetails) {
                put("description", product.description?.let(::htmlToText))
                put("link", product.link)
                put("tags", buildJsonArray { product.tags?.forEach { add(it.name) } })
                put("types", buildJsonArray { product.types?.forEach { add(it.name) } })
                put("additionalLinks", buildJsonArray {
                    product.additionalLinks?.forEach { link ->
                        add(buildJsonObject {
                            put("type", link.type)
                            put("name", link.name)
                            put("link", link.link)
                        })
                    }
                })
            }
            put("latestRelease", latestRelease?.let {
                buildJsonObject {
                    put("version", it.version)
                    put("date", YearMonthDay.of(it.date.toJavaLocalDate()).asIsoString)
                    put("type", it.type.jsonId)
                }
            } ?: JsonNull)
        }
    }

    fun majorRelease(release: MajorRelease): JsonObject {
        return buildJsonObject {
            put("version", release.version)
            put("date", release.date.asIsoString)
        }
    }

    fun priceChange(change: PriceChange): JsonObject {
        return buildJsonObject {
            put("date", change.date.asIsoString)
            put("prices", buildJsonObject {
                for ((customerType, price) in change.prices.entries.sortedBy { it.key }) {
                    put(customerType.toolName, price.setScale(2, RoundingMode.HALF_UP))
                }
            })
        }
    }

    private val htmlTag = Regex("<[^>]+>")
    private val blankLines = Regex("\\n\\s*\\n+")

    /**
     * @return The text of HTML, with line breaks for paragraphs and list items, which needs fewer tokens than the HTML.
     */
    fun htmlToText(html: String): String {
        return html
            .replace(Regex("(?i)<br\\s*/?>|</p>|</li>|</h[1-6]>"), "\n")
            .replace(Regex("(?i)<li[^>]*>"), "- ")
            .replace(htmlTag, "")
            .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
            .lines().joinToString("\n") { it.trim() }
            .replace(blankLines, "\n")
            .trim()
    }
}
