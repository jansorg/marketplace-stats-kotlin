/*
 * Copyright (c) 2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client.model

import dev.ja.marketplace.services.Country
import dev.ja.marketplace.services.JetBrainsProductCode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A filter of the download statistics. The service applies the filters by release ([update]) and by product
 * ([productCode]), also together and for unique downloads, and ignores the filters by country and major version.
 * The group of the downloads without a known product (`unknown`) can't be filtered, the service answers it with 400.
 */
@Serializable
data class DownloadFilter(
    @SerialName("name")
    val type: DownloadFilterType,
    @SerialName("value")
    val value: String
) {
    companion object {
        fun update(updateId: Int): DownloadFilter {
            return DownloadFilter(DownloadFilterType.Update, updateId.toString())
        }

        fun country(country: Country): DownloadFilter {
            return DownloadFilter(DownloadFilterType.Country, country.printableName)
        }

        fun productCode(productCode: JetBrainsProductCode): DownloadFilter {
            return DownloadFilter(DownloadFilterType.ProductCode, productCode.code)
        }

        fun majorVersion(majorVersion: String): DownloadFilter {
            return DownloadFilter(DownloadFilterType.MajorVersion, majorVersion)
        }
    }
}