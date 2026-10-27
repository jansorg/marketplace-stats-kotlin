/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.churn

import dev.ja.marketplace.client.LicenseInfo
import dev.ja.marketplace.client.YearMonthDayRange
import dev.ja.marketplace.client.model.LicensePeriod

/**
 * @param period The license period of the churn rate, or `null` for the combined churn rate of the licenses of all
 *  periods, e.g. annual, monthly and periods, which the Marketplace adds later.
 * @return If the license is counted by the churn rate of the [period]: a paid license of the period. Free licenses
 *  aren't counted, but a license, which continues as a free license or with another period, isn't churned.
 *  Licenses without validity, e.g. perpetual licenses, never churn, the churn processors skip them.
 */
fun LicenseInfo.isCountedByChurn(period: LicensePeriod?): Boolean {
    return isPaidLicense && (period == null || sale.licensePeriod == period)
}

/** The tooltip of the columns of the combined churn rate, see [isCountedByChurn]. */
const val COMBINED_CHURN_TOOLTIP = "Combined churn rate of the paid licenses of all license periods (annual, monthly, …): " +
        "the share of the paid licenses, which were active at the start of the period and aren't active at its end. " +
        "Annual licenses only churn at their renewal date."

/**
 * @return The processor of the churn within the [range]: the licenses, which were active at the day before its start
 *  and aren't active at its end, like the churn of the overview.
 */
fun licenseChurnProcessor(range: YearMonthDayRange): LicenseChurnProcessor {
    return LicenseChurnProcessor(range.start.add(0, 0, -1), range.end)
}
