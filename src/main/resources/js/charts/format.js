/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

/**
 * Formatting of values and dates in charts.
 * Numbers are formatted with the locale of the server, so that the charts match the tables.
 */

export const NO_VALUE = "—";

/** Periods of chart values, ordered from the shortest to the longest. */
export const PERIODS = ["day", "month", "year"];

export const SECONDS_PER_DAY = 24 * 60 * 60;

/** The maximum number of decimals of values without a metric, e.g. of the columns of tables. */
export const DEFAULT_FRACTION_DIGITS = 2;

/**
 * @param {string} isoDate Date like `2026-10-23`.
 * @return {number} Timestamp in seconds, as used by uPlot.
 */
export function isoDateToTimestamp(isoDate) {
    return Date.parse(isoDate + "T00:00:00Z") / 1000;
}

/**
 * @param {string} unit Currency code, `%`, `count` or `ratio`, e.g. of the quick ratio.
 * @return {boolean} If the unit is a monetary amount.
 */
export function isAmountUnit(unit) {
    return unit !== "%" && unit !== "count" && unit !== "ratio";
}

/**
 * @return {string} Title of a y-axis for values of the given unit.
 */
export function unitTitle(unit) {
    switch (unit) {
        case "%":
            return "Percentage (%)";
        case "count":
            return "Count";
        case "ratio":
            return "Ratio";
        default:
            return "Amount (" + unit + ")";
    }
}

/**
 * Dates are shown with the precision of the period, e.g. `2026-10` for a month.
 *
 * @param {number|null|undefined} timestamp Timestamp in seconds.
 * @param {string} period One of {@link PERIODS}.
 */
export function formatDate(timestamp, period) {
    if (timestamp === null || timestamp === undefined) {
        return NO_VALUE;
    }
    const isoDate = new Date(timestamp * 1000).toISOString().substring(0, 10);
    switch (period) {
        case "year":
            return isoDate.substring(0, 4);
        case "month":
            return isoDate.substring(0, 7);
        default:
            return isoDate;
    }
}

/**
 * @param {string|undefined} locale BCP 47 language tag, e.g. `de-DE`. The default locale of the browser is used if undefined.
 */
export function createFormatter(locale) {
    // the formats of the numbers of decimals, created when they're used first
    const numberFormats = new Map();
    const numberFormat = digits => numberFormats.get(digits)
        ?? numberFormats.set(digits, new Intl.NumberFormat(locale, {maximumFractionDigits: digits})).get(digits);
    // a fixed number of decimals aligns the values in tables
    const fixedFormats = new Map();
    const fixedFormat = digits => fixedFormats.get(digits)
        ?? fixedFormats.set(digits, new Intl.NumberFormat(locale, {minimumFractionDigits: digits, maximumFractionDigits: digits})).get(digits);
    const compactNumberFormat = new Intl.NumberFormat(locale, {notation: "compact", maximumFractionDigits: 1});
    const changeFormat = new Intl.NumberFormat(locale, {minimumFractionDigits: 1, maximumFractionDigits: 1});
    const dayFormat = new Intl.DateTimeFormat(locale, {day: "numeric", month: "short", timeZone: "UTC"});
    const dateFormat = new Intl.DateTimeFormat(locale, {dateStyle: "medium", timeZone: "UTC"});

    return {
        /**
         * Value with its unit, e.g. `12.5 %` or `1,234.56 USD`.
         *
         * @param {number} [fractionDigits] The maximum number of decimals, e.g. of a metric, see ChartMetric.fractionDigits.
         */
        value(value, unit, fractionDigits = DEFAULT_FRACTION_DIGITS) {
            if (value === null || value === undefined || Number.isNaN(value)) {
                return NO_VALUE;
            }
            const text = numberFormat(fractionDigits).format(value);
            switch (unit) {
                case "%":
                    return text + " %";
                case "count":
                case "ratio":
                    return text;
                default:
                    return text + " " + unit;
            }
        },

        /**
         * Value with its unit and a fixed number of decimals, which aligns the values of a column,
         * e.g. `12.50 %`, `1,234.00 USD` or `17`. Counts have no decimals, unless the value has decimals.
         *
         * @param {number} [fractionDigits] The number of decimals, the maximum of counts, see {@link value}.
         */
        fixedValue(value, unit, fractionDigits = DEFAULT_FRACTION_DIGITS) {
            if (value === null || value === undefined || Number.isNaN(value)) {
                return NO_VALUE;
            }
            if (unit === "count") {
                return numberFormat(fractionDigits).format(value);
            }
            if (unit === "ratio") {
                return fixedFormat(fractionDigits).format(value);
            }
            return fixedFormat(fractionDigits).format(value) + " " + unit;
        },

        /** Change of a value with its sign, e.g. `+12.5 %` or `−0.4 pp` (percentage points). */
        change({value, unit}) {
            const sign = value > 0 ? "+" : value < 0 ? "−" : "±";
            return sign + changeFormat.format(Math.abs(value)) + (unit === "pp" ? " pp" : " %");
        },

        /** Short number for the ticks of an axis, e.g. `1.2K`. */
        compact(value) {
            return compactNumberFormat.format(value);
        },

        /** Day and month for the ticks of a date axis, e.g. `23 Oct.` */
        day(timestamp) {
            return dayFormat.format(new Date(timestamp * 1000));
        },

        /** A date with its year, e.g. `31.12.2025` or `Dec 31, 2025`. */
        date(timestamp) {
            return dateFormat.format(new Date(timestamp * 1000));
        },
    };
}
