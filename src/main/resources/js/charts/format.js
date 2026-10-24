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

/**
 * @param {string} isoDate Date like `2026-10-23`.
 * @return {number} Timestamp in seconds, as used by uPlot.
 */
export function isoDateToTimestamp(isoDate) {
    return Date.parse(isoDate + "T00:00:00Z") / 1000;
}

/**
 * @param {string} unit Currency code, `%` or `count`.
 * @return {boolean} If the unit is a monetary amount.
 */
export function isAmountUnit(unit) {
    return unit !== "%" && unit !== "count";
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
    const numberFormat = new Intl.NumberFormat(locale, {maximumFractionDigits: 2});
    const compactNumberFormat = new Intl.NumberFormat(locale, {notation: "compact", maximumFractionDigits: 1});
    // a fixed number of decimals aligns the values in tables
    const fixedFormat = new Intl.NumberFormat(locale, {minimumFractionDigits: 2, maximumFractionDigits: 2});
    const changeFormat = new Intl.NumberFormat(locale, {minimumFractionDigits: 1, maximumFractionDigits: 1});
    const dayFormat = new Intl.DateTimeFormat(locale, {day: "numeric", month: "short", timeZone: "UTC"});

    return {
        /** Value with its unit, e.g. `12.5 %` or `1,234.56 USD`. */
        value(value, unit) {
            if (value === null || value === undefined || Number.isNaN(value)) {
                return NO_VALUE;
            }
            switch (unit) {
                case "%":
                    return numberFormat.format(value) + " %";
                case "count":
                    return numberFormat.format(value);
                default:
                    return numberFormat.format(value) + " " + unit;
            }
        },

        /**
         * Value with its unit and a fixed number of decimals, which aligns the values of a column,
         * e.g. `12.50 %`, `1,234.00 USD` or `17`. Counts have no decimals, unless the value has decimals.
         */
        fixedValue(value, unit) {
            if (value === null || value === undefined || Number.isNaN(value)) {
                return NO_VALUE;
            }
            if (unit === "count") {
                return numberFormat.format(value);
            }
            return fixedFormat.format(value) + " " + unit;
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
    };
}
