/*
 * Copyright (c) 2023-2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.churn

import dev.ja.marketplace.data.format.Formatters
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
    /** The license period, `null` for the combined churn of all periods. */
    val period: LicensePeriod?,
) {
    /**
     * @return The churn of the items of both results, e.g. of the licenses of several plugins, whose IDs are unrelated.
     *  The results must be of the same period and dates.
     */
    operator fun plus(other: ChurnResult<T>): ChurnResult<T> {
        require(period == other.period && previousPeriodMarkerDate == other.previousPeriodMarkerDate && currentPeriodMarkerDate == other.currentPeriodMarkerDate) {
            "The churn results have different periods or dates"
        }
        val previousActive = previousActiveItemCount + other.previousActiveItemCount
        val churned = churnedItemCount + other.churnedItemCount
        return copy(
            churnRate = if (previousActive == 0) 0.0 else churned.toDouble() / previousActive.toDouble(),
            previousActiveItemCount = previousActive,
            activeItemCount = activeItemCount + other.activeItemCount,
            churnedItemCount = churned,
        )
    }

    /**
     * @param pluginId The plugin of the link to the churned licenses, `null` for a rate without link, e.g. of all plugins.
     * @param isIncomplete If the churn rate of a period, which hasn't ended yet, is shown.
     */
    fun getRenderedChurnRate(pluginId: PluginId?, isIncomplete: Boolean = false): Any {
        if (previousActiveItemCount == 0 || churnRate.isNaN()) {
            return "—"
        }

        val rate = churnRate.asPercentageValue() ?: return "—"
        val percentage: Any = when (churnRate) {
            // PercentageValue.ZERO is rendered as "no value", but no churn is a valid result
            0.0 -> PercentageValue(BigDecimal.ZERO).toString()
            else -> rate
        }

        val linkedChurnRate = if (pluginId == null) percentage else LinkedChurnRate(
            percentage,
            rate,
            previousPeriodMarkerDate,
            currentPeriodMarkerDate,
            period,
            pluginId,
            churnedCount = churnedItemCount,
            previousActiveCount = previousActiveItemCount,
        )
        return when {
            isIncomplete -> IncompleteValue(linkedChurnRate)
            else -> linkedChurnRate
        }
    }

    fun getChurnRateTooltip(isIncomplete: Boolean = false): String {
        val tooltip = "${Formatters.formatInteger(churnedItemCount)} of ${Formatters.formatInteger(previousActiveItemCount)} churned"
        return when {
            isIncomplete -> "$tooltip until ${currentPeriodMarkerDate.asIsoString}\nIncomplete: licenses expiring later in this period may still be renewed"
            else -> tooltip
        }
    }
}