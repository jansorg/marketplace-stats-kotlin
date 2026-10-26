/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.topCountries

import dev.ja.marketplace.churn.ChurnValidities
import dev.ja.marketplace.churn.LicenseChurnProcessor
import dev.ja.marketplace.client.LicenseId
import dev.ja.marketplace.client.LicenseInfo
import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.client.model.LicensePeriod
import dev.ja.marketplace.data.PercentageValue

/**
 * The churn of the paid licenses of a country in the 12 months before today, calculated like the annual churn of the
 * overview: the share of the licenses, which were active a year ago and aren't active today.
 *
 * The country of a license is the country of the customer, who held it a year ago. The churn is calculated for all
 * licenses, so a license, which another customer renewed, e.g. in another country, isn't churned.
 */
class CountryChurn(licenses: List<LicenseInfo>, today: YearMonthDay) {
    /**
     * @param previouslyActive The licenses, which were active a year ago.
     * @param churned The licenses of [previouslyActive], which aren't active today.
     */
    data class Result(val previouslyActive: Int, val churned: Int) {
        /** `null` if no license was active a year ago. */
        val churnRate: PercentageValue?
            get() = if (previouslyActive == 0) null else PercentageValue.of(churned, previouslyActive)
    }

    val previouslyActiveDate: YearMonthDay = today.add(-1, 0, 0)
    val activeDate: YearMonthDay = today

    private val results = HashMap<LicensePeriod, Map<String, Result>>()

    init {
        val churnValidities = ChurnValidities(licenses)
        for (period in listOf(LicensePeriod.Annual, LicensePeriod.Monthly)) {
            val processor = LicenseChurnProcessor(previouslyActiveDate, activeDate)
            val countries = HashMap<LicenseId, String>()
            for (license in licenses) {
                val validity = churnValidities.validity(license) ?: continue
                val isAccepted = license.sale.licensePeriod == period && license.isPaidLicense
                processor.processValue(license, validity, isAccepted)
                if (isAccepted && previouslyActiveDate in validity) {
                    countries[license.id] = license.sale.customer.country
                }
            }
            val churned = processor.churnedIds()
            results[period] = countries.entries
                .groupBy({ it.value }, { it.key })
                .mapValues { (_, licenseIds) -> Result(licenseIds.size, licenseIds.count { it in churned }) }
        }
    }

    fun result(country: String, period: LicensePeriod): Result {
        return results[period]!![country] ?: Result(0, 0)
    }
}
