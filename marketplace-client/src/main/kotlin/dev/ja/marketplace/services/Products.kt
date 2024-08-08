/*
 * Copyright (c) 2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.services

import kotlinx.datetime.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ProductWithReleases(
    @SerialName("code")
    val code: JetBrainsProductCode,
    @SerialName("name")
    val name: String,
    @SerialName("link")
    val link: String? = null,
    @SerialName("description")
    val description: String? = null,
    @SerialName("tags")
    val tags: List<ProductIdNameValue>? = null,
    @SerialName("types")
    val types: List<ProductIdNameValue>? = null,
    @SerialName("categories")
    val categories: List<String>? = null,
    @SerialName("releases")
    val releases: List<ProductRelease>,
    @SerialName("forSale")
    val forSale: Boolean? = null,
    @SerialName("productFamilyName")
    val productFamilyName: String? = null,
    @SerialName("additionalLinks")
    val additionalLinks: List<ProductAdditionalLink>?,
    @SerialName("salesCode")
    val salesCode: String? = null,
    @SerialName("intellijProductCode")
    val intellijProductCode: String? = null,
    @SerialName("alternativeCodes")
    val alternativeCodes: List<String>? = null,
    @SerialName("distributions")
    val distributions: Map<String, ProductDistribution>? = null,
    @SerialName("excludeFromUpdatesXml")
    val excludeFromUpdatesXml: Boolean? = null,
)

@Serializable
data class ProductDistribution(
    @SerialName("name")
    val name: String,
    @SerialName("extension")
    val extension: String,
)

@Serializable
data class ProductIdNameValue(
    @SerialName("id")
    val id: String,
    @SerialName("name")
    val name: String
)

@Serializable
data class ProductAdditionalLink(
    @SerialName("type")
    val type: String,
    @SerialName("name")
    val name: String,
    @SerialName("link")
    val link: String,
)

@Serializable
enum class ProductReleaseType(val jsonId: String) {
    @SerialName("release")
    Release("release"),

    @SerialName("rc")
    ReleaseCandidate("rc"),

    @SerialName("eap")
    EAP("eap"),

    @SerialName("preview")
    Preview("preview"),
}

@Serializable
enum class ProductPatchType(val jsonId: String) {
    @SerialName("mac")
    MacIntel("mac"),

    @SerialName("macM1")
    MacAppleSilicon("macM1"),

    @SerialName("unix")
    UNIX("unix"),

    @SerialName("win")
    Windows("win"),
}

@Serializable
data class ProductPatchRelease(
    @SerialName("fromBuild")
    val fromBuild: String,
    @SerialName("link")
    val downloadLink: String,
    @SerialName("size")
    val downloadFileSizeBytes: Long,
    @SerialName("checksumLink")
    val checksumLink: String,
)

@Serializable
data class ProductReleaseDownload(
    @SerialName("link")
    val link: String,
    @SerialName("checksumLink")
    val checksumUrl: String,
    @SerialName("size")
    val fileSizeBytes: Long,
    @SerialName("signedChecksumLink")
    val signedChecksumLink: String? = null,
)

@Serializable
data class ProductRelease(
    @SerialName("date")
    val date: LocalDate,
    @SerialName("type")
    val type: ProductReleaseType,
    @SerialName("printableReleaseType")
    val printableReleaseType: String? = null,
    @SerialName("majorVersion")
    val majorVersion: String,
    @SerialName("version")
    val version: String,
    @SerialName("build")
    val build: BuildNumber? = null,
    @SerialName("notesLink")
    val notesLink: String? = null,
    @SerialName("whatsnew")
    val whatsNewHtml: String? = null,
    @SerialName("licenseRequired")
    val licenseRequired: Boolean? = null,
    @SerialName("patches")
    val patches: Map<ProductPatchType, List<ProductPatchRelease>>,
    @SerialName("downloads")
    val downloads: Map<String, ProductReleaseDownload>? = null,
    @SerialName("uninstallFeedbackLinks")
    val uninstallFeedbackLinks: Map<String, String>? = null,
    @SerialName("isSecurityCritical")
    val isSecurityCritical: Boolean = false,
)

data class Products(val productsWithReleases: List<ProductWithReleases>) {
    fun getProduct(code: JetBrainsProductCode): ProductWithReleases? {
        return productsWithReleases.firstOrNull { it.code == code }
    }
}