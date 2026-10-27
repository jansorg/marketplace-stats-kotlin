/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace

/**
 * The attributes of the cells of large tables, e.g. of the 35,000 rows of a list of licenses. JTE omits attributes
 * without a value, but keeps the space before each of them, and writes attributes with complex expressions, e.g. a
 * lambda, also without a value. These attributes are only written with a value and without extra spaces.
 */
object HtmlAttributes {
    /**
     * @param attributes The names and values of the attributes, attributes with a `null` or an empty value are left out,
     *  e.g. an empty tooltip.
     * @return The attributes with their escaped values, separated by a space, e.g. `class="num" title="a &amp; b"`.
     */
    fun of(vararg attributes: Pair<String, Any?>): String {
        val result = StringBuilder()
        for ((name, attributeValue) in attributes) {
            val value = attributeValue?.toString()?.takeIf(String::isNotEmpty)
            if (value != null) {
                if (result.isNotEmpty()) {
                    result.append(' ')
                }
                result.append(name).append("=\"")
                escape(value, result)
                result.append('"')
            }
        }
        return result.toString()
    }

    private fun escape(value: String, target: StringBuilder) {
        for (char in value) {
            when (char) {
                '&' -> target.append("&amp;")
                '"' -> target.append("&#34;")
                '\'' -> target.append("&#39;")
                '<' -> target.append("&lt;")
                '>' -> target.append("&gt;")
                else -> target.append(char)
            }
        }
    }
}
