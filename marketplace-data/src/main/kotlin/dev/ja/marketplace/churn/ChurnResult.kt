/*
 * Copyright (c) 2023-2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.churn

import dev.ja.marketplace.client.model.LicensePeriod
import dev.ja.marketplace.client.PluginId
import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.data.LinkedChurnRate
import dev.ja.marketplace.data.PercentageValue
import dev.ja.marketplace.data.asPercentageValue
import java.math.BigDecimal

data class ChurnResult<T>(
    val churnRate: Double,
    val previousActiveItemCount: Int,
    val activeItemCount: Int,
    val churnedItemCount: Int,
    val previousPeriodMarkerDate: YearMonthDay,
    val currentPeriodMarkerDate: YearMonthDay,
    val period: LicensePeriod,
) {
    fun getRenderedChurnRate(pluginId: PluginId): Any {
        if (previousActiveItemCount == 0 || churnRate.isNaN()) {
            return "—"
        }

        val percentage: Any = when (churnRate) {
            // PercentageValue.ZERO is rendered as "no value", but no churn is a valid result
            0.0 -> PercentageValue(BigDecimal.ZERO).toString()
            else -> churnRate.asPercentageValue() ?: return "—"
        }

        return LinkedChurnRate(
            percentage,
            previousPeriodMarkerDate,
            currentPeriodMarkerDate,
            period,
            pluginId
        )
    }

    val churnRateTooltip: String
        get() {
            return "$churnedItemCount of $previousActiveItemCount churned"
        }
}