/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

enum class PageAnalyticsTimeFrame(val requestParameter: String) {
    LastMonth("LastMonth"),
    LastYear("LastYear"),
    AllTime("AllTime"),
}

/**
 * Visitors of the plugin page on the Marketplace, collected with Google Analytics.
 * The Marketplace warns that the data may be inaccurate because of the sampling of Google Analytics.
 */
@Serializable
data class PluginPageAnalytics(
    /** Values by day. Days without visitors are missing, and the data of longer time frames may be incomplete. */
    @SerialName("dailyStatistic")
    val days: List<PageAnalyticsDay> = emptyList(),
    @SerialName("countriesStatisticDto")
    val countries: List<PageAnalyticsCountry> = emptyList(),
    /** Referrers of the visits, e.g. `(direct)` or `google`. */
    @SerialName("sourcesStatisticDto")
    val sources: List<PageAnalyticsSource> = emptyList(),
    /** `false` if Google Analytics marked the data as unreliable. */
    @SerialName("isReliable")
    val isReliable: Boolean = false,
)

@Serializable
data class PageAnalyticsDay(
    /** Day in the format `yyyyMMdd`. */
    @SerialName("day")
    val day: String,
    @SerialName("sessions")
    val sessions: Int = 0,
    @SerialName("users")
    val users: Int = 0,
    @SerialName("pageViews")
    val pageViews: Int = 0,
)

@Serializable
data class PageAnalyticsCountry(
    @SerialName("country")
    val country: String,
    @SerialName("sessions")
    val sessions: Int = 0,
    @SerialName("users")
    val users: Int = 0,
)

@Serializable
data class PageAnalyticsSource(
    @SerialName("source")
    val source: String,
    @SerialName("sessions")
    val sessions: Int = 0,
)
