/*
 * Copyright (c) 2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client

data class SalesWithLicensesInfo(val sales: List<PluginSale>, val licenses: List<LicenseInfo>)