/*
 * Copyright (c) 2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client.model

import dev.ja.marketplace.client.Marketplace
import dev.ja.marketplace.client.UserId
import io.ktor.http.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class JetBrainsAccountInfo(
    @SerialName("id")
    val id: UserId,
    @SerialName("name")
    val name: String? = null,
    @SerialName("link")
    val link: String? = null,
    @SerialName("hubLogin")
    val hubLogin: String? = null,
    @SerialName("icon")
    val iconUrl: String? = null,
    @SerialName("showMarketoCheckbox")
    val showMarketoCheckbox: Boolean? = null,
    @SerialName("isJetBrains")
    val isJetBrains: Boolean = false,
    // used in release info data and for the developers request
    @SerialName("personalVendorId")
    val personalVendorId: Int? = null,
) {
    fun getLinkUrl(frontendUrl: Url = Marketplace.MarketplaceFrontendUrl): Url? {
        this.link ?: return null
        return URLBuilder(frontendUrl).also {
            it.encodedPath = this.link
        }.build()
    }
}