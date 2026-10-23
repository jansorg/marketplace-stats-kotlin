/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.exchangeRate

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class EcbRateTableTest {
    private val csv = """
        Date,USD,JPY,CYP,GBP,
        2024-04-15,1.0656,164.62,N/A,0.85365,
        2024-04-12,1.0652,163.16,N/A,0.85435,
        2024-04-11,1.0729,164.29,N/A,0.85538,
        2019-01-04,1.1789,133.73,0.58231,0.7111,
    """.trimIndent()

    private val table = EcbRateTable.parseCsv(csv.byteInputStream(), minDate = LocalDate.of(2020, 1, 1))

    @Test
    fun parsing() {
        assertEquals(LocalDate.of(2024, 4, 11), table.firstDate)
        assertEquals(LocalDate.of(2024, 4, 15), table.lastDate)

        assertEquals(BigDecimal("1.0652"), table.euroRate("USD", LocalDate.of(2024, 4, 12)))
        assertEquals(BigDecimal("163.16"), table.euroRate("JPY", LocalDate.of(2024, 4, 12)))
        assertEquals(BigDecimal.ONE, table.euroRate("EUR", LocalDate.of(2024, 4, 12)))

        assertNull(table.euroRate("CYP", LocalDate.of(2024, 4, 12)), "N/A must be unavailable")
        assertNull(table.euroRate("CHF", LocalDate.of(2024, 4, 12)), "unknown currency")
        assertNull(table.euroRate("USD", LocalDate.of(2024, 4, 13)), "no rates published on weekends")
        assertNull(table.euroRate("USD", LocalDate.of(2019, 1, 4)), "rows before the min date are skipped")
    }

    @Test
    fun closestDate() {
        // exact match
        assertEquals(LocalDate.of(2024, 4, 12), table.closestDate(LocalDate.of(2024, 4, 12)))
        // Saturday is closer to Friday
        assertEquals(LocalDate.of(2024, 4, 12), table.closestDate(LocalDate.of(2024, 4, 13)))
        // Sunday is closer to Monday
        assertEquals(LocalDate.of(2024, 4, 15), table.closestDate(LocalDate.of(2024, 4, 14)))
        // before and after the available data
        assertEquals(LocalDate.of(2024, 4, 11), table.closestDate(LocalDate.of(2020, 1, 1)))
        assertEquals(LocalDate.of(2024, 4, 15), table.closestDate(LocalDate.of(2030, 1, 1)))
    }

    @Test
    fun closestDateTieUsesEarlierDate() {
        val tie = EcbRateTable.parseCsv("Date,USD,\n2024-04-15,1.0,\n2024-04-11,2.0,\n".byteInputStream())
        // 2024-04-13 is two days after the 11th and two days before the 15th
        assertEquals(LocalDate.of(2024, 4, 11), tie.closestDate(LocalDate.of(2024, 4, 13)))
    }
}
