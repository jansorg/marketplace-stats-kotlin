/*
 * Copyright (c) 2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.services

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
    val categories: List<ProductCategory>? = null,
    @SerialName("releases")
    val releases: List<ProductRelease>,
    @SerialName("forSale")
    val forSale: Boolean = false,
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
    val distributions: Map<ProductDownloadType, ProductDistribution>? = null,
    @SerialName("excludeFromUpdatesXml")
    val excludeFromUpdatesXml: Boolean? = null,
) {
    override fun toString(): String {
        return "ProductWithReleases(code=$code, name=$name, distributions=${distributions?.size ?: 0}, releases=${releases.size})"
    }
}