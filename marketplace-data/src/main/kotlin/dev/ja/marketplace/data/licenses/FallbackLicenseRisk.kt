/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.licenses

import dev.ja.marketplace.client.LicenseId
import dev.ja.marketplace.client.LicenseInfo
import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.client.model.CustomerInfo
import dev.ja.marketplace.client.model.LicensePeriod
import dev.ja.marketplace.client.model.PluginReleaseDate
import dev.ja.marketplace.client.times
import dev.ja.marketplace.data.PercentageValue
import dev.ja.marketplace.data.trackers.MonetaryAmountTracker
import dev.ja.marketplace.exchangeRate.ExchangeRates
import javax.money.CurrencyUnit
import javax.money.MonetaryAmount

/**
 * The licenses, whose perpetual fallback license already covers the latest version of the plugin, and the revenue,
 * which is lost if they aren't renewed.
 *
 * Without a new version, the customers of these licenses can keep using the latest version without renewing.
 * The risk grows when the latest version is older than a year, because then even the subscriptions,
 * which were renewed for a year, cover it.
 *
 * A license is affected if its latest paid subscription is active today, i.e. it ends today or later, and its
 * [FallbackVersion] is the latest version of the plugin. Like [FallbackVersion], all purchases of a license are used,
 * also those of other customers, e.g. if the license was transferred. The customer of a license is the customer of its
 * latest paid purchase.
 *
 * The revenue at risk is the annual recurring revenue of the affected licenses: the amount of the latest paid purchase
 * of each license, normalized to a year (monthly licenses are multiplied by 12). It's converted into the display currency
 * at the date of the sale, like the annual recurring revenue of the other pages. The billed amounts include all
 * discounts.
 *
 * @param releases The release dates of the versions of the plugin, which the Marketplace uses for fallback licenses.
 */
class FallbackLicenseRisk(
    licenses: Iterable<LicenseInfo>,
    releases: List<PluginReleaseDate>,
    exchangeRates: ExchangeRates,
    val today: YearMonthDay = YearMonthDay.now(),
) {
    data class AffectedLicense(
        val licenseId: LicenseId,
        /** The customer of the latest paid purchase. */
        val customer: CustomerInfo,
        val fallbackVersion: FallbackVersion,
        /** The paid purchase with the latest end. */
        val latestPurchase: LicenseInfo,
        /** The latest purchase, normalized to a year and converted into the display currency. */
        val annualRevenue: MonetaryAmount,
    ) {
        val period: LicensePeriod
            get() = latestPurchase.sale.licensePeriod
    }

    data class AffectedCustomer(
        val customer: CustomerInfo,
        /** Ordered by the end of their subscriptions. */
        val licenses: List<AffectedLicense>,
        val annualRevenue: MonetaryAmount,
    ) {
        /** The end of the subscription, which ends first. */
        val firstSubscriptionEnd: YearMonthDay
            get() = licenses.first().fallbackVersion.subscriptionEnd
    }

    val currency: CurrencyUnit = exchangeRates.targetCurrency

    /** The latest version of the plugin, the version with the latest release date. */
    val latestRelease: PluginReleaseDate? = releases.maxByOrNull { it.date }

    /** The day, on which the latest version is a year old. */
    val latestReleaseYearOldDate: YearMonthDay? = latestRelease?.date?.add(1, 0, 0)

    /** `true` if the latest version was released a year ago or earlier. */
    val isLatestReleaseYearOld: Boolean = latestReleaseYearOldDate != null && latestReleaseYearOldDate <= today

    /** The affected licenses, ordered by the end of their subscriptions. */
    val affectedLicenses: List<AffectedLicense>

    /** The customers of the affected licenses, ordered by their revenue at risk, highest first. */
    val affectedCustomers: List<AffectedCustomer>

    /** The annual recurring revenue of the affected licenses. */
    val revenueAtRisk: MonetaryAmount

    /** The number of licenses with an active paid subscription. */
    val activeLicenseCount: Int

    /** The annual recurring revenue of all licenses with an active paid subscription, calculated like [revenueAtRisk]. */
    val activeAnnualRevenue: MonetaryAmount

    /** The share of [revenueAtRisk] of [activeAnnualRevenue]. */
    val revenueAtRiskShare: PercentageValue
        get() = PercentageValue.of(revenueAtRisk, activeAnnualRevenue)

    init {
        val paidSubscriptions = licenses
            .filter { it.isPaidLicense && it.isSubscriptionLicense }
            .groupBy { it.id }

        val affected = mutableListOf<AffectedLicense>()
        val activeRevenue = MonetaryAmountTracker(exchangeRates)
        val revenueAtRisk = MonetaryAmountTracker(exchangeRates)
        var activeLicenseCount = 0
        for ((licenseId, purchases) in paidSubscriptions) {
            val latestPurchase = purchases.maxWith(compareBy<LicenseInfo> { it.validity!!.end }.thenBy { it.sale.date })
            if (latestPurchase.validity!!.end < today) {
                continue
            }

            activeLicenseCount++
            val annualRevenue = MonetaryAmountTracker(exchangeRates)
            annualRevenue.addAnnualized(latestPurchase)
            activeRevenue.addAnnualized(latestPurchase)

            val fallbackVersion = FallbackVersion.of(licenseId, purchases, releases) ?: continue
            if (fallbackVersion.isLatestVersion) {
                revenueAtRisk.addAnnualized(latestPurchase)
                affected += AffectedLicense(
                    licenseId,
                    latestPurchase.sale.customer,
                    fallbackVersion,
                    latestPurchase,
                    annualRevenue.getTotalAmount(),
                )
            }
        }

        this.affectedLicenses = affected.sortedWith(
            compareBy<AffectedLicense> { it.fallbackVersion.subscriptionEnd }.thenBy { it.licenseId }
        )
        this.affectedCustomers = affectedLicenses
            .groupBy { it.customer.code }
            .values
            .map { customerLicenses ->
                val customerRevenue = MonetaryAmountTracker(exchangeRates)
                customerLicenses.forEach { customerRevenue.addAnnualized(it.latestPurchase) }
                AffectedCustomer(customerLicenses.first().customer, customerLicenses, customerRevenue.getTotalAmount())
            }
            .sortedWith(
                compareByDescending<AffectedCustomer> { it.annualRevenue }
                    .thenBy { it.firstSubscriptionEnd }
                    .thenBy { it.customer.code }
            )
        this.revenueAtRisk = revenueAtRisk.getTotalAmount()
        this.activeLicenseCount = activeLicenseCount
        this.activeAnnualRevenue = activeRevenue.getTotalAmount()
    }

    private fun MonetaryAmountTracker.addAnnualized(license: LicenseInfo) {
        val factor = when (license.sale.licensePeriod) {
            LicensePeriod.Monthly -> 12
            LicensePeriod.Annual, LicensePeriod.Perpetual -> 1
        }
        add(license.sale.date, license.amountUSD * factor, license.amount * factor)
    }
}
