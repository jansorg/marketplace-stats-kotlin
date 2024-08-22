/*
 * Copyright (c) 2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client.model

import dev.ja.marketplace.services.JetBrainsProductCode

data class ProductDownload(val productCode: JetBrainsProductCode, val productName: String?, val downloads: Long)