/*
 * Copyright (c) 2023-2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.churn

import dev.ja.marketplace.client.model.LicensePeriod
import dev.ja.marketplace.client.YearMonthDayRange

interface ChurnProcessor<T> {
    fun init()

    fun processValue(
        value: T,
        validity: YearMonthDayRange,
        isAcceptedValue: Boolean,
        isExplicitRenewal: Boolean = false
    )

    fun getResult(period: LicensePeriod): ChurnResult<T>
}