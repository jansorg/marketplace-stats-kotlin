/*
 * Copyright (c) 2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client.model

import dev.ja.marketplace.client.PluginUrl
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PluginVendor(
    @SerialName("type")
    val type: String,
    @SerialName("name")
    override val name: String? = null,
    @SerialName("url")
    val url: PluginUrl? = null,
    @SerialName("totalPlugins")
    val totalPlugins: Int? = null,
    @SerialName("totalUsers")
    val totalUsers: Int? = null,
    @SerialName("link")
    val link: String? = null,
    @SerialName("publicName")
    val publicName: String? = null,
    @SerialName("email")
    val email: String? = null,
    @SerialName("countryCode")
    val countryCode: String? = null,
    @SerialName("country")
    val country: String? = null,
    @SerialName("isVerified")
    override val isVerified: Boolean? = null,
    @SerialName("vendorId")
    val vendorId: Int? = null,
    @SerialName("isTrader")
    val isTrader: Boolean? = null,
    @SerialName("servicesDescription")
    val servicesDescription: List<String>? = null,
    @SerialName("id")
    val id: Int? = null,
) : PluginVendorInformation