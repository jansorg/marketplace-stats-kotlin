/*
 * Copyright (c) 2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class DownloadResponseItem(
    @SerialName("name")
    val name: String,
    @SerialName("value")
    val value: Long,
    @SerialName("nameComment")
    val comment: String? = null,
) {
    /**
     * The label of a group of the downloads by product: the name of the product, e.g. "IntelliJ IDEA". The API groups
     * the downloads without a known product, e.g. from the website, as `unknown`, without a name.
     */
    val productLabel: String
        get() = comment ?: if (name == UNKNOWN_PRODUCT) "Unknown product" else name

    companion object {
        /** The name of the group of the downloads without a known product. */
        const val UNKNOWN_PRODUCT = "unknown"
    }
}