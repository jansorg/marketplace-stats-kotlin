/*
 * Copyright (c) 2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace

import dev.ja.marketplace.client.CustomerInfo
import dev.ja.marketplace.client.CustomerType

object TestCustomers {
    val PersonDummy = CustomerInfo(100, "DE", CustomerType.Personal, "Dummy Person")
    val OrganizationDummy = CustomerInfo(200, "DE", CustomerType.Organization, "Dummy Person")
}