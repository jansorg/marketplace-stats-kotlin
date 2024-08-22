/*
 * Copyright (c) 2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client.model

import kotlinx.serialization.Serializable

@Serializable
enum class PluginSearchOrderBy(val parameterValue: String?) {
    Relevance(null),
    Name("name"),
    Downloads("downloads"),
    Rating("rating"),
    PublishDate("publish date"),
    UpdateDate("update date"),
}