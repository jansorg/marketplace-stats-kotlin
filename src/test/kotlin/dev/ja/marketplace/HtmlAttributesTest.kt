/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class HtmlAttributesTest {
    @Test
    fun onlyAttributesWithValues() {
        // a column without attributes is the constant empty string, a cell without own attributes the string of its column
        assertSame("", HtmlAttributes.column(null, null))
        assertSame("", HtmlAttributes.column(null, ""))
        val num = HtmlAttributes.column(null, "num")
        assertEquals("class=\"num\"", num)
        assertSame(num, HtmlAttributes.cell(num, title = null))
        // an empty tooltip has no effect
        assertSame(num, HtmlAttributes.cell(num, title = ""))
        assertSame("", HtmlAttributes.cell(title = "", chartIncomplete = false))
        assertEquals("class=\"num\" data-sort=\"12\"", HtmlAttributes.cell(num, sort = 12L))
        assertEquals("data-sort=\"12\"", HtmlAttributes.cell(sort = 12L))
        assertEquals(
            "colspan=\"2\" title=\"t\" data-sort=\"-1\" data-chart-col=\"3\" data-chart-value=\"1.5\" data-chart-unit=\"%\" " +
                    "data-chart-incomplete=\"true\" data-chart-share=\"0.03\" data-chart-history=\"h\"",
            HtmlAttributes.cell(HtmlAttributes.column(2, null), "t", -1L, 3, "1.5", "%", true, "0.03", "h"),
        )
    }

    @Test
    fun escapedValues() {
        assertEquals("title=\"a &amp; b &lt;c&gt; &#34;d&#34; &#39;e&#39;\"", HtmlAttributes.cell(title = "a & b <c> \"d\" 'e'"))
        // the JSON of the history of a row
        assertEquals("data-chart-history=\"{&#34;unit&#34;:&#34;%&#34;}\"", HtmlAttributes.cell(chartHistory = "{\"unit\":\"%\"}"))
    }

    @Test
    fun theBuilderOfTheThreadIsReused() {
        // a large value doesn't change the next cells
        val history = "x".repeat(100_000)
        assertEquals("data-chart-history=\"$history\"", HtmlAttributes.cell(chartHistory = history))
        assertEquals("class=\"num\" title=\"b\"", HtmlAttributes.cell(HtmlAttributes.column(null, "num"), title = "b"))
        assertEquals("title=\"a\"", HtmlAttributes.cell(title = "a"))
    }
}
