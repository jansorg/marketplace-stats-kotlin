/*
 * Copyright (c) 2023-2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.churn

import dev.ja.marketplace.client.model.LicensePeriod
import dev.ja.marketplace.client.PluginId
import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.data.IncompleteValue
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
    /**
     * @param isIncomplete If the churn rate of a period, which hasn't ended yet, is shown.
     */
    fun getRenderedChurnRate(pluginId: PluginId, isIncomplete: Boolean = false): Any {
        if (previousActiveItemCount == 0 || churnRate.isNaN()) {
            return "—"
        }

        val rate = churnRate.asPercentageValue() ?: return "—"
        val percentage: Any = when (churnRate) {
            // PercentageValue.ZERO is rendered as "no value", but no churn is a valid result
            0.0 -> PercentageValue(BigDecimal.ZERO).toString()
            else -> rate
        }

        val linkedChurnRate = LinkedChurnRate(
            percentage,
            rate,
            previousPeriodMarkerDate,
            currentPeriodMarkerDate,
            period,
            pluginId
        )
        return when {
            isIncomplete -> IncompleteValue(linkedChurnRate)
            else -> linkedChurnRate
        }
    }

    fun getChurnRateTooltip(isIncomplete: Boolean = false): String {
        val tooltip = "$churnedItemCount of $previousActiveItemCount churned"
        return when {
            isIncomplete -> "$tooltip until ${currentPeriodMarkerDate.asIsoString}\nIncomplete: licenses expiring later in this period may still be renewed"
            else -> tooltip
        }
    }
}