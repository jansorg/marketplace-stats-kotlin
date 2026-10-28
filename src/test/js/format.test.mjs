/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

import {test} from "node:test";
import assert from "node:assert/strict";
import {createFormatter, formatDate, isAmountUnit, isoDateToTimestamp, NO_VALUE, unitTitle} from "../../main/resources/js/charts/format.js";

test("dates have the precision of the period", () => {
    const timestamp = isoDateToTimestamp("2026-10-23");
    assert.equal(formatDate(timestamp, "day"), "2026-10-23");
    assert.equal(formatDate(timestamp, "month"), "2026-10");
    assert.equal(formatDate(timestamp, "year"), "2026");
    // the Sunday of the week, e.g. of the middle of the week, at which a chart shows its value
    assert.equal(formatDate(timestamp, "week"), "2026-10-25");
    assert.equal(formatDate(isoDateToTimestamp("2026-10-25"), "week"), "2026-10-25");
    assert.equal(formatDate(null, "day"), NO_VALUE);
});

test("values are formatted with their unit and the locale", () => {
    const formatter = createFormatter("de-DE");
    assert.equal(formatter.value(1234.5, "USD"), "1.234,5 USD");
    assert.equal(formatter.value(12.345, "%"), "12,35 %");
    assert.equal(formatter.value(7, "count"), "7");
    assert.equal(formatter.value(null, "USD"), NO_VALUE);
    assert.equal(formatter.value(Number.NaN, "USD"), NO_VALUE);
    assert.equal(createFormatter("en-US").value(1234.5, "USD"), "1,234.5 USD");
});

test("values in tables have a fixed number of decimals", () => {
    const formatter = createFormatter("en-US");
    assert.equal(formatter.fixedValue(1234.5, "USD"), "1,234.50 USD");
    assert.equal(formatter.fixedValue(52, "EUR"), "52.00 EUR");
    assert.equal(formatter.fixedValue(5.5, "%"), "5.50 %");
    assert.equal(formatter.fixedValue(17, "count"), "17");
    assert.equal(formatter.fixedValue(null, "USD"), NO_VALUE);
});

test("values of metrics have the number of decimals of their metric", () => {
    const formatter = createFormatter("en-US");
    // e.g. the average of the active paid licenses
    assert.equal(formatter.value(1234.5, "count", 0), "1,235");
    assert.equal(formatter.fixedValue(1234.5, "count", 0), "1,235");
    // e.g. the average of the trials per day
    assert.equal(formatter.value(0.44, "count", 1), "0.4");
    assert.equal(formatter.fixedValue(3, "count", 1), "3");
    assert.equal(formatter.fixedValue(12.5, "%", 1), "12.5 %");
    assert.equal(formatter.fixedValue(0.5, "ratio", 0), "1");
    // without a number, e.g. of the columns of tables, values have up to two decimals
    assert.equal(formatter.value(1234.567, "count", undefined), "1,234.57");
    assert.equal(formatter.fixedValue(5.5, "%", undefined), "5.50 %");
});

test("changes have a sign and one decimal", () => {
    const formatter = createFormatter("en-US");
    assert.equal(formatter.change({value: 12, unit: "%"}), "+12.0 %");
    assert.equal(formatter.change({value: -0.44, unit: "pp"}), "−0.4 pp");
    assert.equal(formatter.change({value: 0, unit: "%"}), "±0.0 %");
});

test("days for the ticks of date axes", () => {
    assert.equal(createFormatter("en-US").day(isoDateToTimestamp("2026-10-23")), "Oct 23");
    assert.equal(createFormatter("de-DE").day(isoDateToTimestamp("2026-10-23")), "23. Okt.");
});

test("units", () => {
    assert.equal(isAmountUnit("USD"), true);
    assert.equal(isAmountUnit("%"), false);
    assert.equal(isAmountUnit("count"), false);
    assert.equal(isAmountUnit("ratio"), false);
    assert.equal(unitTitle("ratio"), "Ratio");
    assert.equal(unitTitle("EUR"), "Amount (EUR)");
    assert.equal(unitTitle("%"), "Percentage (%)");
    assert.equal(unitTitle("count"), "Count");
});

test("dates are formatted with the locale of the page", () => {
    const timestamp = isoDateToTimestamp("2025-12-31");
    assert.equal(createFormatter("de-DE").date(timestamp), "31.12.2025");
    assert.equal(createFormatter("en-US").date(timestamp), "Dec 31, 2025");
});

test("the tooltip dates have the period of the values: the month, the week from Monday to Sunday and the day", () => {
    const formatter = createFormatter("en-US");
    // the value of October at its last day, of the current month at today
    assert.equal(formatter.month(isoDateToTimestamp("2026-10-31")), "October 2026");
    assert.equal(formatter.month(isoDateToTimestamp("2026-01-15")), "January 2026");
    // the value of a week at its Sunday, of the current week at today, e.g. a Wednesday. Intl separates the dates
    // with thin spaces.
    const week = iso => formatter.week(isoDateToTimestamp(iso)).replace(/\s/g, " ");
    assert.equal(week("2026-10-25"), "Oct 19 – 25, 2026");
    assert.equal(week("2026-10-21"), "Oct 19 – 25, 2026");
    assert.equal(week("2026-12-31"), "Dec 28, 2026 – Jan 3, 2027");
    assert.equal(formatter.date(isoDateToTimestamp("2026-10-28")), "Oct 28, 2026");
});
