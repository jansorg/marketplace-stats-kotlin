/*
 * Copyright (c) 2024-2026 Joachim Ansorg.
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
    @SerialName("marketplaceVendorId")
    val marketplaceVendorId: Int? = null,
    @SerialName("showEmail")
    val showEmail: Boolean? = null,
    /** The postal address and phone of the vendor, only for the vendor's own plugins. */
    @SerialName("details")
    val details: PluginVendorDetails? = null,
) : PluginVendorInformation

/**
 * The contact details of a vendor.
 */
@Serializable
data class PluginVendorDetails(
    @SerialName("address")
    val address: String? = null,
    @SerialName("city")
    val city: String? = null,
    @SerialName("state")
    val state: String? = null,
    @SerialName("zip")
    val zip: String? = null,
    @SerialName("phone")
    val phone: String? = null,
)