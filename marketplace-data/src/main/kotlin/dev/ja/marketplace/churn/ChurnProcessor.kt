/*
 * Copyright (c) 2023 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.churn

import dev.ja.marketplace.client.YearMonthDayRange

interface ChurnProcessor<ID, T> {
    fun init()

    fun processValue(id: ID, value: T, validity: YearMonthDayRange, isAcceptedValue: Boolean)

    fun getResult(): ChurnResult<T>
}