/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HtmlAttributesTest {
    @Test
    fun onlyAttributesWithValues() {
        assertEquals("", HtmlAttributes.of("class" to null, "title" to null))
        // an empty tooltip has no effect
        assertEquals("class=\"num\"", HtmlAttributes.of("class" to "num", "title" to ""))
        assertEquals("class=\"num\" data-sort=\"12\"", HtmlAttributes.of("colspan" to null, "class" to "num", "title" to null, "data-sort" to 12L))
    }

    @Test
    fun escapedValues() {
        assertEquals("title=\"a &amp; b &lt;c&gt; &#34;d&#34; &#39;e&#39;\"", HtmlAttributes.of("title" to "a & b <c> \"d\" 'e'"))
        // the JSON of the history of a row
        assertEquals("data-chart-history=\"{&#34;unit&#34;:&#34;%&#34;}\"", HtmlAttributes.of("data-chart-history" to "{\"unit\":\"%\"}"))
    }
}
