/*
 * Copyright (c) 2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client.model

import dev.ja.marketplace.client.*
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PluginInfoSummary(
    @SerialName("id")
    override val id: PluginId,
    @SerialName("xmlId")
    override val xmlId: PluginXmlId,
    @SerialName("name")
    override val name: String,
    @SerialName("link")
    override val link: String,
    @SerialName("preview")
    override val previewText: String,
    @SerialName("downloads")
    override val downloads: Int,
    @SerialName("pricingModel")
    override val pricingModel: PluginPricingModel,
    @SerialName("rating")
    val rating: Double,
    @SerialName("hasSource")
    val hasSource: Boolean,
    @SerialName("tags")
    override val tagNames: List<PluginTagName> = emptyList(),
    @SerialName("icon")
    override val iconUrlPath: String? = null,
    @SerialName("cdate")
    @Serializable(CDateSerializer::class)
    val createdTimestamp: Instant? = null,
    @SerialName("vendor")
    val vendor: PluginVendorShort? = null,
    @SerialName("organization")
    val organizationName: String? = null,
    @SerialName("target")
    val target: String? = null,
) : PluginInfoExtended {
}