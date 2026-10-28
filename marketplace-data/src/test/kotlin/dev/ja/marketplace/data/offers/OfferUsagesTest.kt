/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.offers

import dev.ja.marketplace.TestCustomers
import dev.ja.marketplace.client.LicenseInfo
import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.client.currency.MarketplaceCurrencies
import dev.ja.marketplace.client.model.*
import dev.ja.marketplace.exchangeRate.EcbExchangeRateData
import dev.ja.marketplace.exchangeRate.ExchangeRates
import org.javamoney.moneta.FastMoney
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import javax.money.MonetaryAmount

class OfferUsagesTest {
    private val exchangeRates = ExchangeRates("USD", EcbExchangeRateData())

    @Test
    fun offerNameOfDiscount() {
        assertEquals("Spring sale", OfferDiscounts.offerName(PluginSaleItemDiscount("\"Spring sale\" offer", 20.0)))
        assertEquals("A \"quoted\" name", OfferDiscounts.offerName(PluginSaleItemDiscount("\"A \"quoted\" name\" offer", 20.0)))
        assertNull(OfferDiscounts.offerName(PluginSaleItemDiscount("40% continuity discount", 40.0)))
        assertNull(OfferDiscounts.offerName(PluginSaleItemDiscount("Reseller discount", 5.0)))
    }

    @Test
    fun lostAmount() {
        // 75 paid after 25 % off: the price before the discount was 100
        assertAmount(25, OfferDiscounts.lostAmount(usd(75), 25.0))
        assertAmount(60, OfferDiscounts.lostAmount(usd(40), 60.0))
        // rounded to the 5 decimals of the converted amounts
        assertEquals(BigDecimal("6.66667"), OfferDiscounts.lostAmount(usd(10), 40.0)!!.number.numberValue(BigDecimal::class.java))
        assertNull(OfferDiscounts.lostAmount(usd(0), 100.0))
        assertNull(OfferDiscounts.lostAmount(usd(10), null))
        assertNull(OfferDiscounts.lostAmount(usd(10), 0.0))
    }

    @Test
    fun salesAreCountedForTheOfferOfTheirDiscount() {
        val summer = offer(1, "Summer sale", percent = 50)
        val free = offer(2, "Free for students", percent = 100)
        val usages = OfferUsages(listOf(summer, free), exchangeRates)

        licenses(
            sale("s1", YearMonthDay(2025, 6, 1), 50, 1, discount("\"Summer sale\" offer", 50.0)),
            sale("s2", YearMonthDay(2025, 7, 1), 25, 2, discount("\"Summer sale\" offer", 50.0), discount("50% continuity discount", 50.0)),
            sale("s3", YearMonthDay(2025, 8, 1), 0, 1, discount("\"Free for students\" offer", 100.0)),
            sale("s4", YearMonthDay(2025, 9, 1), 100, 3),
        ).forEach(usages::add)

        val summerUsage = usages.usage(summer.id)!!
        assertEquals(2, summerUsage.licenses)
        assertEquals(2, summerUsage.sales)
        assertEquals(2, summerUsage.customers)
        assertEquals(0, summerUsage.freeLicenses)
        assertAmount(75, summerUsage.paid.getTotalAmount())
        // the price before the discount of the offer: 100 and 50, after the continuity discount
        assertAmount(75, summerUsage.lost.getTotalAmount())
        assertEquals(YearMonthDay(2025, 6, 1), summerUsage.firstUse)
        assertEquals(YearMonthDay(2025, 7, 1), summerUsage.lastUse)

        val freeUsage = usages.usage(free.id)!!
        assertEquals(1, freeUsage.licenses)
        assertEquals(1, freeUsage.freeLicenses)
        assertEquals(1, freeUsage.licensesWithoutLostAmount)
        assertAmount(0, freeUsage.paid.getTotalAmount())

        // the continuity discount isn't an offer
        val continuity = usages.continuityDiscounts["50% continuity discount"]!!
        assertEquals(1, continuity.licenses)
        assertAmount(25, continuity.lost.getTotalAmount())
        assertTrue(usages.otherOffers.isEmpty())
        assertTrue(usages.otherDiscounts.isEmpty())
    }

    @Test
    fun offersOutsideOfTheListAreOtherOffers() {
        val usages = OfferUsages(listOf(offer(1, "Summer sale", percent = 50)), exchangeRates)
        licenses(
            sale("s1", YearMonthDay(2025, 6, 1), 0, 1, discount("\"Offer of someone else\" offer", 100.0)),
            sale("s2", YearMonthDay(2025, 6, 2), 0, 2, discount("\"Offer of someone else\" offer", 100.0)),
        ).forEach(usages::add)

        assertNull(usages.usage(1))
        val other = usages.otherOffers["Offer of someone else"]!!
        assertEquals(2, other.licenses)
        assertEquals(2, other.freeLicenses)
        assertEquals(setOf(100.0), other.percents)
    }

    @Test
    fun offersWithTheSameNameAreMatchedByDate() {
        val first = offer(1, "Black Friday", percent = 30, start = YearMonthDay(2024, 11, 20), end = YearMonthDay(2024, 11, 30))
        val second = offer(2, "Black Friday", percent = 40, start = YearMonthDay(2025, 11, 20), end = YearMonthDay(2025, 11, 30))
        val usages = OfferUsages(listOf(first, second), exchangeRates)
        licenses(
            sale("s1", YearMonthDay(2024, 11, 25), 70, 1, discount("\"Black Friday\" offer", 30.0)),
            sale("s2", YearMonthDay(2025, 11, 25), 60, 2, discount("\"Black Friday\" offer", 40.0)),
            // a renewal after the end of the offer belongs to the latest offer, which started before
            sale("s3", YearMonthDay(2025, 1, 10), 70, 3, discount("\"Black Friday\" offer", 30.0)),
        ).forEach(usages::add)

        assertEquals(2, usages.usage(first.id)!!.licenses)
        assertEquals(1, usages.usage(second.id)!!.licenses)
    }

    @Test
    fun specialsOfTheMarketplace() {
        // an offer of the plugin with the name of a special is an offer
        val student = offer(1, "Student discount for teams", percent = 30)
        val usages = OfferUsages(listOf(student), exchangeRates)
        licenses(
            sale("s1", YearMonthDay(2025, 6, 1), 0, 1, discount("\"Student discount\" offer", 100.0)),
            sale("s2", YearMonthDay(2025, 6, 2), 0, 2, discount("\"Graduation discount\" offer", 100.0)),
            sale("s3", YearMonthDay(2025, 6, 3), 90, 3, discount("Subscription volume discount", 10.0)),
            sale("s4", YearMonthDay(2025, 6, 4), 70, 4, discount("\"Student discount for teams\" offer", 30.0)),
            sale("s5", YearMonthDay(2025, 6, 5), 50, 5, discount("Developer Recognition discount", 50.0)),
            sale("s6", YearMonthDay(2025, 6, 6), 50, 6, discount("50% discount for Educational organisations", 50.0)),
            sale("s7", YearMonthDay(2025, 6, 7), 0, 7, discount("\"Educational License Discount\" offer", 100.0)),
            sale("s8", YearMonthDay(2025, 6, 8), 50, 8, discount("50% non-profit discount on commercial plugin", 50.0)),
            sale("s9", YearMonthDay(2025, 6, 9), 0, 9, discount("\"OpenSource discount\" offer", 100.0)),
        ).forEach(usages::add)

        assertEquals(
            setOf(
                "Student discount", "Graduation discount", "Subscription volume discount", "Developer Recognition discount",
                "50% discount for Educational organisations", "Educational License Discount",
                "50% non-profit discount on commercial plugin", "OpenSource discount",
            ),
            usages.marketplaceSpecials.keys,
        )
        assertEquals(1, usages.usage(student.id)!!.licenses)
        assertTrue(usages.otherOffers.isEmpty())
        assertTrue(usages.otherDiscounts.isEmpty())
        // the links of the specials find them
        assertNotNull(usages.usage(OfferUsageKey.OtherOffer("Student discount")))
        assertNotNull(usages.usage(OfferUsageKey.OtherDiscount("Subscription volume discount")))
    }

    @Test
    fun namesOfSpecialsWithoutThePluginName() {
        val plugin = "My Plugin, the IDE"
        assertEquals("Student discount", OfferDiscounts.withoutPluginName("Student discount for My Plugin, the IDE", plugin))
        assertEquals("Graduation discount", OfferDiscounts.withoutPluginName("Graduation discount for my plugin, the ide", plugin))
        assertEquals("50% non-profit discount", OfferDiscounts.withoutPluginName("50% non-profit discount on commercial plugin My Plugin, the IDE", plugin))
        assertEquals(
            "50% discount for Educational organisations",
            OfferDiscounts.withoutPluginName("50% discount for Educational organisations on commercial plugin My Plugin, the IDE", plugin),
        )
        assertEquals("OpenSource discount", OfferDiscounts.withoutPluginName("OpenSource discount on plugin My Plugin, the IDE ", plugin))
        // names without the plugin name and the name of the plugin alone are unchanged
        assertEquals("Educational License Discount", OfferDiscounts.withoutPluginName("Educational License Discount", plugin))
        assertEquals("Student discount for another plugin", OfferDiscounts.withoutPluginName("Student discount for another plugin", plugin))
        assertEquals("My Plugin, the IDE", OfferDiscounts.withoutPluginName("My Plugin, the IDE", plugin))
        assertEquals("Student discount for My Plugin", OfferDiscounts.withoutPluginName("Student discount for My Plugin", ""))
    }

    @Test
    fun resellerDiscountsAreIgnored() {
        val summer = offer(1, "Summer sale", percent = 50)
        val usages = OfferUsages(listOf(summer), exchangeRates)
        licenses(
            sale("s1", YearMonthDay(2025, 6, 1), 50, 1, discount("\"Summer sale\" offer", 50.0), discount("Reseller discount", 10.0)),
            sale("s2", YearMonthDay(2025, 6, 2), 100, 2, discount("Reseller discount", 10.0)),
        ).forEach(usages::add)

        // the reseller discount doesn't reduce the paid amount
        assertAmount(50, usages.usage(summer.id)!!.lost.getTotalAmount())
        assertTrue(usages.otherDiscounts.isEmpty())
        assertTrue(usages.otherOffers.isEmpty())
    }

    @Test
    fun lostAmountUsesThePercentOfEachSale() {
        // the percent of an offer with the same name changed, the sales have their own percents
        val offer = offer(1, "Summer sale", percent = 20)
        val usages = OfferUsages(listOf(offer), exchangeRates)
        licenses(
            sale("s1", YearMonthDay(2025, 6, 1), 80, 1, discount("\"Summer sale\" offer", 20.0)),
            sale("s2", YearMonthDay(2025, 6, 2), 50, 2, discount("\"Summer sale\" offer", 50.0)),
            // without a percent, the lost amount is unknown
            sale("s3", YearMonthDay(2025, 6, 3), 80, 3, discount("\"Summer sale\" offer", null)),
        ).forEach(usages::add)

        val usage = usages.usage(offer.id)!!
        assertAmount(70, usage.lost.getTotalAmount())
        assertEquals(1, usage.licensesWithoutLostAmount)
    }

    @Test
    fun licensesAndCustomersOfAnOffer() {
        val offer = offer(1, "Summer sale", percent = 50)
        val usages = OfferUsages(listOf(offer), exchangeRates)
        val licenses = licenses(
            sale("s1", YearMonthDay(2025, 6, 1), 50, 1, discount("\"Summer sale\" offer", 50.0)),
            sale("s2", YearMonthDay(2025, 6, 2), 100, 2),
            sale("s3", YearMonthDay(2025, 6, 3), 60, 3, discount("40% continuity discount", 40.0)),
        )
        licenses.forEach(usages::add)

        val usage = usages.usage(OfferUsageKey.Offer(offer.id))!!
        assertEquals(listOf("s1"), licenses.filter { it in usage }.map { it.sale.ref })
        assertTrue(usage.hasCustomer(1))
        assertFalse(usage.hasCustomer(2))
        assertEquals(listOf("s3"), licenses.filter { it in usages.usage(OfferUsageKey.OtherDiscount("40% continuity discount"))!! }.map { it.sale.ref })
        assertNull(usages.usage(OfferUsageKey.OtherOffer("Unknown")))

        // the parameters of the links
        val key = OfferUsageKey.OtherDiscount("40% continuity discount")
        assertEquals(key, OfferUsageKey.fromParameters(mapOf(key.queryParameter)::get))
        assertEquals(OfferUsageKey.Offer(1), OfferUsageKey.fromParameters(mapOf("offer" to "1")::get))
        assertNull(OfferUsageKey.fromParameters(mapOf("offer" to "x")::get))
        assertEquals("/customers?pId=7&discount=40%25+continuity+discount&rows=500", LinkedOfferUsage(1.toBigInteger(), OfferUsageList.Customers, key, 7).href)
    }

    private fun offer(id: Int, name: String, percent: Int?, start: YearMonthDay = YearMonthDay(2025, 1, 1), end: YearMonthDay? = null): PluginOffer {
        return PluginOffer(id, name, 1, start, listOf("Annual", "Monthly"), percent, null, end)
    }

    private fun discount(description: String, percent: Double?) = PluginSaleItemDiscount(description, percent)

    private fun sale(ref: String, date: YearMonthDay, amountUSD: Int, customerId: Int, vararg discounts: PluginSaleItemDiscount): PluginSale {
        val amount = usd(amountUSD)
        val customer = TestCustomers.PersonDummy.copy(code = customerId)
        val item = SubscriptionPluginSaleItem(
            PluginSaleItemType.New, listOf("license-$ref"), amount, amount, discounts.toList(), date.rangeTo(date.add(1, 0, -1)),
        )
        return PluginSale(ref, date, amount, amount, LicensePeriod.Annual, customer, null, listOf(item))
    }

    private fun licenses(vararg sales: PluginSale): List<LicenseInfo> = LicenseInfo.createFrom(sales.toList())

    private fun usd(amount: Int): MonetaryAmount = FastMoney.of(amount, MarketplaceCurrencies.USD)

    private fun assertAmount(expected: Int, actual: MonetaryAmount?) {
        assertNotNull(actual)
        assertEquals(MarketplaceCurrencies.USD, actual!!.currency)
        assertEquals(0, BigDecimal(expected).compareTo(actual.number.numberValue(BigDecimal::class.java)), "expected $expected, was $actual")
    }
}
