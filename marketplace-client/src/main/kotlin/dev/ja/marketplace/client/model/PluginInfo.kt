/*
 * Copyright (c) 2024-2025 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client.model

import dev.ja.marketplace.client.*
import io.ktor.http.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
@Serializable
data class PluginInfo(
    @SerialName("id")
    override val id: PluginId,
    @SerialName("name")
    override val name: String,
    @SerialName("link")
    override val link: String,
    @SerialName("approve")
    val approve: Boolean,
    @SerialName("xmlId")
    override val xmlId: String,
    @SerialName("description")
    val description: String,
    @SerialName("customIdeList")
    val customIdeList: Boolean,
    @SerialName("preview")
    override val previewText: String? = null,
    @SerialName("docText")
    val docText: String? = null,
    @SerialName("email")
    val email: String? = null,
    /**
     * This date is not the timestamp when the plugin was published, but (probably) the timestamp when the
     * last update of the plugin data was made, e.g. by uploading a new release to update the description.
     */
    @SerialName("cdate")
    @Serializable(CDateSerializer::class)
    val lastUpdatedTimestamp: Instant? = null,
    @SerialName("family")
    val family: ProductFamily,
    @SerialName("copyright")
    val copyright: String? = null,
    @SerialName("downloads")
    override val downloads: Int,
    @SerialName("purchaseInfo")
    val purchaseInfo: PluginPurchaseInfo? = null,
    @SerialName("vendor")
    val vendor: PluginVendor? = null,
    @SerialName("pluginXmlVendor")
    val pluginXmlVendor: String? = null,
    @SerialName("urls")
    val urls: PluginUrls? = null,
    @SerialName("tags")
    val tags: List<PluginTag> = emptyList(),
    @SerialName("hasUnapprovedUpdate")
    val hasUnapprovedUpdate: Boolean,
    @SerialName("pricingModel")
    override val pricingModel: PluginPricingModel,
    @SerialName("screens")
    val screens: List<PluginResourceUrl> = emptyList(),
    @SerialName("icon")
    override val iconUrlPath: String? = null,
    @SerialName("isHidden")
    val isHidden: Boolean,
    @SerialName("isMonetizationAvailable")
    val isMonetizationAvailable: Boolean? = null,
) : PluginInfoExtended {
    override val tagNames: List<PluginTagName>
        get() = tags.map(PluginTag::name)

    fun getLinkUrl(frontendUrl: Url = Marketplace.MarketplaceFrontendUrl): Url {
        return URLBuilder(frontendUrl).also {
            it.encodedPath = this.link
        }.build()
    }

    fun getIconUrl(frontendUrl: Url = Marketplace.MarketplaceFrontendUrl): Url? {
        this.iconUrlPath ?: return null
        return URLBuilder(frontendUrl).also {
            it.encodedPath = this.iconUrlPath
        }.build()
    }
}