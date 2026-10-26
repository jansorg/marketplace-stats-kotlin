/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client

import dev.ja.marketplace.client.currency.MarketplaceCurrencies
import dev.ja.marketplace.client.model.CustomerType
import dev.ja.marketplace.client.model.LicensePeriod
import dev.ja.marketplace.client.model.SalesAnalyticsReportRow
import dev.ja.marketplace.client.model.SalesReportLicenseType
import org.javamoney.moneta.FastMoney
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SalesReportCsvTest {
    @Test
    fun quotedValues() {
        val records = SalesReportCsv.parse("a,b,c\r\n\"x, y\",\"say \"\"hi\"\"\",\"line\nbreak\"\n1,,3")
        assertEquals(listOf(listOf("a", "b", "c"), listOf("x, y", "say \"hi\"", "line\nbreak"), listOf("1", "", "3")), records)
    }

    @Test
    fun analyticsReport() {
        val csv = """
            code,processed_date,customer_type,license_type,billing_period,sum_quantity,revenue_usd
            PPLUGINID,2025-03-14,Organization,Commercial,Annual,2,199.80
            PPLUGINID,2025-03-15,Personal,NewType,none,1,9.90
        """.trimIndent() + "\n"
        val rows = SalesReportCsv.analyticsReport(csv)
        assertEquals(2, rows.size)
        assertEquals(YearMonthDay(2025, 3, 14), rows[0].date)
        assertEquals(CustomerType.Organization, rows[0].customerType)
        assertEquals(SalesReportLicenseType.Commercial, rows[0].licenseType)
        assertEquals(LicensePeriod.Annual, rows[0].billingPeriod)
        assertEquals(2, rows[0].quantity)
        assertEquals(FastMoney.of(199.80, MarketplaceCurrencies.USD), rows[0].amountUSD)
        assertEquals(CustomerType.Individual, rows[1].customerType)
        // unknown values don't break the client
        assertNull(rows[1].licenseType)
        assertNull(rows[1].billingPeriod)
    }

    @Test
    fun analyticsReportWithByteOrderMarkAndLongDecimals() {
        val csv = "\uFEFFcode,processed_date,customer_type,license_type,billing_period,sum_quantity,revenue_usd\n" +
                "PPLUGINID,2025-03-14,Organization,Commercial,Perpetual,2,49.900000000000006\n"
        val row = SalesReportCsv.analyticsReport(csv).single()
        assertEquals("PPLUGINID", row.productCode)
        assertEquals(LicensePeriod.Perpetual, row.billingPeriod)
        // sums of the report can have more decimal places than supported by FastMoney
        assertEquals(FastMoney.of(49.9, MarketplaceCurrencies.USD), row.amountUSD)
    }

    @Test
    fun fullReport() {
        val csv = """
            code,processed_date,customer_type,customer_name,customer_country_iso,via_reseller,license_type,billing_period,quantity,amount_in_usd,is_discounted,customer_code,order_ref,purchase_id
            PPLUGINID,2025-03-14,Organization,"Example, Inc.",DE,true,Commercial,Monthly,1,19.90,false,123456,R1234567,98765432
            PPLUGINID,2025-03-15,Personal,<hidden>,US,false,Trial,none,1,0.00,false,654321,R7654321,12345678
        """.trimIndent()
        val rows = SalesReportCsv.fullReport(csv)
        assertEquals(2, rows.size)
        assertEquals("Example, Inc.", rows[0].customerName)
        assertEquals("DE", rows[0].customerCountryIsoCode)
        assertTrue(rows[0].isViaReseller)
        assertFalse(rows[0].isDiscounted)
        assertEquals(123456, rows[0].customerId)
        assertEquals("R1234567", rows[0].orderRef)
        assertEquals("98765432", rows[0].purchaseId)
        assertEquals(SalesReportLicenseType.Trial, rows[1].licenseType)
        assertNull(rows[1].billingPeriod)
    }

    @Test
    fun emptyReport() {
        assertTrue(SalesReportCsv.analyticsReport("").isEmpty())
        assertTrue(SalesReportCsv.analyticsReport("code,processed_date\n").isEmpty())
    }

    @Test
    fun invalidRowsAreSkippedAndEmptyCellsAreKept() {
        val csv = """
            code,processed_date,customer_type,customer_name,customer_country_iso,via_reseller,license_type,billing_period,quantity,amount_in_usd,is_discounted,customer_code,order_ref,purchase_id
            PPLUGINID,2025-03-14 10:15:00,Student,Name,DE,false,Commercial,Quarterly,,19.90,false,,R1,P1
            PPLUGINID,2025-03-15,Personal,Name,DE,false,Commercial,Annual,1.0,,false,1,R2,P2
            PPLUGINID,not a date,Personal,Name,DE,false,Commercial,Annual,1,9.90,false,1,R3,P3
            PPLUGINID,2025-03-16,Personal,Name,DE,false,Commercial,Annual,1.0,9.90,false,1,R4,P4
        """.trimIndent() + "\n"
        val rows = SalesReportCsv.fullReport(csv)
        // the rows without an amount and with an invalid date are skipped
        assertEquals(listOf("R1", "R4"), rows.map { it.orderRef })

        val first = rows[0]
        assertEquals(YearMonthDay(2025, 3, 14), first.date)
        assertEquals(CustomerType.Unknown, first.customerType)
        assertEquals(LicensePeriod.Unknown, first.billingPeriod)
        assertEquals(0, first.quantity)
        assertNull(first.customerId)
        assertEquals(1, rows[1].quantity)
    }

    @Test
    fun missingRequiredColumnFailsTheReport() {
        val csv = "code,processed_date,customer_type\nPPLUGINID,2025-03-14,Personal\n"
        val error = assertThrows<IllegalStateException> { SalesReportCsv.analyticsReport(csv) }
        assertEquals("The columns [revenue_usd] of the analytics report are missing", error.message)
        // an empty report
        assertEquals(emptyList<SalesAnalyticsReportRow>(), SalesReportCsv.analyticsReport("code,processed_date,customer_type\n"))
    }
}
