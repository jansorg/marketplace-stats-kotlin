/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.offers

import dev.ja.marketplace.client.PluginId
import java.math.BigInteger
import java.net.URLEncoder

/**
 * A number of the usage of an offer or a discount, linked to the list of the licenses or customers with it.
 */
data class LinkedOfferUsage(
    val count: BigInteger,
    val list: OfferUsageList,
    val key: OfferUsageKey,
    val pluginId: PluginId,
) {
    /**
     * The address of the list, e.g. `/licenses?pId=1&offer=2&rows=500`, with the short parameter of the plugin. The lists can be long, e.g. of the
     * continuity discounts, they show [OfferUsageList.DEFAULT_ROWS] rows and a button to show all rows.
     */
    val href: String
        get() {
            val (name, value) = key.queryParameter
            return "${list.path}?pId=$pluginId&$name=${URLEncoder.encode(value, Charsets.UTF_8)}&rows=${OfferUsageList.DEFAULT_ROWS}"
        }
}

/** The lists of the licenses and customers, which are filtered by the offer or the discount. */
enum class OfferUsageList(val path: String) {
    Licenses("/licenses"),
    Customers("/customers");

    companion object {
        /** The number of rows of the linked lists. */
        const val DEFAULT_ROWS = 500
    }
}
