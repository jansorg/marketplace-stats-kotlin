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
    assert.equal(unitTitle("EUR"), "Amount (EUR)");
    assert.equal(unitTitle("%"), "Percentage (%)");
    assert.equal(unitTitle("count"), "Count");
});
