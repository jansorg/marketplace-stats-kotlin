/*
 * Copyright (c) 2023 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data

import dev.ja.marketplace.client.YearMonthDayRange

interface WithDateRange {
    val dateRange: YearMonthDayRange
}