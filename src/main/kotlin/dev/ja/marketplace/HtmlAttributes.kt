/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace

/**
 * The attributes of the cells of large tables, e.g. of the hundreds of thousands of cells of a list of licenses. JTE omits attributes
 * without a value, but keeps the space before each of them, and writes attributes with complex expressions, e.g. a
 * lambda, also without a value. These attributes are only written with a value and without extra spaces.
 *
 * It's called for each cell, it allocates as little as possible. The attributes are split into the ones of the column,
 * e.g. its class, which are the same for all cells of the column and are written once per column ([column]), and the
 * ones of a cell, e.g. its tooltip ([cell]). A cell without attributes of its own is the string of its column, e.g. the
 * constant empty string of a column with the rules of [CellStyles], the attributes of the other cells are written into
 * a builder of the thread, numbers without converting them into strings first.
 */
object HtmlAttributes {
    /** The builder of the attributes of a thread, it's reused for the cells of a page. */
    private val builders = ThreadLocal.withInitial { StringBuilder(INITIAL_CAPACITY) }

    /**
     * @return The attributes of a column, which are the same for all its cells, e.g. `class="num"`, the empty string
     *  without them. It's passed to [cell] for each cell of the column.
     */
    fun column(colspan: Int? = null, cssClass: String? = null): String {
        if (colspan == null && cssClass.isNullOrEmpty()) {
            return ""
        }
        val result = StringBuilder()
        appendNumber(result, "colspan", colspan?.toLong())
        append(result, "class", cssClass)
        return result.toString()
    }

    /**
     * @param column The attributes of the column, see [column].
     * @return The attributes of a cell with their escaped values, separated by a space, e.g.
     *  `class="num" title="a &amp; b"`: the attributes of its column and its own attributes. Attributes with a `null` or
     *  an empty value are left out, e.g. an empty tooltip. A cell without own attributes is [column] itself.
     */
    fun cell(
        column: String = "",
        title: String? = null,
        sort: Long? = null,
        chartCol: Int? = null,
        chartValue: String? = null,
        chartUnit: String? = null,
        chartIncomplete: Boolean = false,
        chartShare: String? = null,
        chartHistory: String? = null,
    ): String {
        if (title.isNullOrEmpty() && sort == null && chartCol == null && chartValue.isNullOrEmpty() && chartUnit.isNullOrEmpty()
            && !chartIncomplete && chartShare.isNullOrEmpty() && chartHistory.isNullOrEmpty()
        ) {
            return column
        }

        val result = builders.get()
        result.setLength(0)
        result.append(column)
        append(result, "title", title)
        appendNumber(result, "data-sort", sort)
        appendNumber(result, "data-chart-col", chartCol?.toLong())
        append(result, "data-chart-value", chartValue)
        append(result, "data-chart-unit", chartUnit)
        append(result, "data-chart-incomplete", if (chartIncomplete) "true" else null)
        append(result, "data-chart-share", chartShare)
        append(result, "data-chart-history", chartHistory)
        val attributes = result.toString()
        // a large builder, e.g. of the history of a row, isn't kept
        if (result.capacity() > MAX_KEPT_CAPACITY) {
            builders.set(StringBuilder(INITIAL_CAPACITY))
        }
        return attributes
    }

    private fun append(target: StringBuilder, name: String, value: String?) {
        if (value.isNullOrEmpty()) {
            return
        }
        startAttribute(target, name)
        escape(value, target)
        target.append('"')
    }

    private fun appendNumber(target: StringBuilder, name: String, value: Long?) {
        if (value == null) {
            return
        }
        startAttribute(target, name)
        target.append(value).append('"')
    }

    private fun startAttribute(target: StringBuilder, name: String) {
        if (target.isNotEmpty()) {
            target.append(' ')
        }
        target.append(name).append("=\"")
    }

    private fun escape(value: String, target: StringBuilder) {
        // most values, e.g. classes and numbers, have no characters to escape and are appended at once
        if (value.none { it == '&' || it == '"' || it == '\'' || it == '<' || it == '>' }) {
            target.append(value)
            return
        }
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

    private const val INITIAL_CAPACITY = 256
    private const val MAX_KEPT_CAPACITY = 16 * 1024
}
