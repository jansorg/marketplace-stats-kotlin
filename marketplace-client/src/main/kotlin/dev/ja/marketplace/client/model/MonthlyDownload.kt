/*
 * Copyright (c) 2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client.model

import dev.ja.marketplace.client.YearMonthDay

data class MonthlyDownload(val firstOfMonth: YearMonthDay, val downloads: Long)