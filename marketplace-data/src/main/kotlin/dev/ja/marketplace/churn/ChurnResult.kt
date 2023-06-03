/*
 * Copyright (c) 2023 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.churn

import dev.ja.marketplace.data.asPercentageValue

data class ChurnResult<T>(
    val churnRate: Double,
    val previousActiveItemCount: Int,
    val activeItemCount: Int,
    val churnedItemCount: Int
) {
    val renderedChurnRate: Any?
        get() {
            if (churnRate == 0.0 || churnRate.isNaN()) {
                return "—"
            }
            return churnRate.asPercentageValue()
        }

    val churnRateTooltip: String
        get() {
            return "$churnedItemCount of $previousActiveItemCount churned"
        }
}