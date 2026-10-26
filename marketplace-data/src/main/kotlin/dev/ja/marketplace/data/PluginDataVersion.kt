/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data

/**
 * The version of the data of a plugin, see [PluginData.dataVersion], e.g. to reuse values calculated from the data.
 *
 * The cached client returns the same objects until it loads the data again, they're compared by their identity first.
 * Data, which is assembled for each request, e.g. the trials of the months without a persistent cache, is compared by
 * its content, which is much faster than calculating the values again.
 */
class PluginDataVersion(private val values: List<Any?>) {
    /** @return If the data of [other] is the same, so that values calculated from it are the same. */
    fun isSameAs(other: PluginDataVersion): Boolean {
        return values.size == other.values.size && values.indices.all { values[it] === other.values[it] || values[it] == other.values[it] }
    }
}
