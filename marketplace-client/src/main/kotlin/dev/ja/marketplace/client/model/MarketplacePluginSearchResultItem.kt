/*
 * Copyright (c) 2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client.model

import dev.ja.marketplace.client.CDateSerializer
import dev.ja.marketplace.client.PluginId
import dev.ja.marketplace.client.PluginTagName
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MarketplacePluginSearchResultItem(
    @SerialName("id")
    override val id: PluginId,
    @SerialName("name")
    override val name: String,
    @SerialName("link")
    override val link: String? = null,
    @SerialName("xmlId")
    override val xmlId: String,
    @SerialName("preview")
    override val previewText: String? = null,
    @SerialName("downloads")
    override val downloads: Int,
    @SerialName("pricingModel")
    override val pricingModel: PluginPricingModel,
    @SerialName("hasSource")
    val hasSource: Boolean,
    @SerialName("tags")
    override val tagNames: List<PluginTagName> = emptyList(),
    @SerialName("organization")
    val organizationName: String? = null,
    @SerialName("icon")
    override val iconUrlPath: String? = null,
    @SerialName("previewImage")
    val previewImageUrlPath: String? = null,
    @SerialName("cdate")
    @Serializable(CDateSerializer::class)
    val createdTimestamp: Instant? = null,
    @SerialName("vendorInfo")
    val vendorInfo: PluginVendorShort? = null,
) : PluginInfoExtended