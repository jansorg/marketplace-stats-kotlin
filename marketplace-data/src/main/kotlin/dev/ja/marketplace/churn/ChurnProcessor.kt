/*
 * Copyright (c) 2023-2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.churn

import dev.ja.marketplace.client.model.LicensePeriod
import dev.ja.marketplace.client.YearMonthDayRange

interface ChurnProcessor<T> {
    /**
     * @param validity Validity of the value. For licenses, use the validity provided by [ChurnValidities],
     *  which covers short gaps between the expiration of a license and its renewal.
     */
    fun processValue(
        value: T,
        validity: YearMonthDayRange,
        isAcceptedValue: Boolean,
    )

    fun getResult(period: LicensePeriod): ChurnResult<T>
}