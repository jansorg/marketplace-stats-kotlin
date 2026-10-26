/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

/**
 * Persistence of the chart setup in the local storage of the browser.
 * Only the selected columns and the options are stored, never the values of the tables.
 */

export const STORAGE_KEY = "marketplace-stats.charts";
/**
 * Version 2 stores the series of metrics with the ID of the metric instead of the table and the column, and the window
 * and the detail level of each chart of metrics. A setup of version 1 is still read: its series of tables are shown
 * as metrics, if their columns have a metric, its levels of the charts are dropped.
 */
const VERSION = 2;
const READ_VERSIONS = [1, VERSION];

/**
 * @typedef {{type: "line"|"pie", table: string, column: string, row?: string, color?: string, style?: "lines"|"bars"}
 *  |{type: "metric", metric: string, window?: string, usesWindow?: boolean, average?: number, label: string, unit: string, measure?: string,
 *   row?: string, color?: string, style?: "lines"|"bars"}} ChartEntry
 * @typedef {{window: string, detail: string}} ChartLevel
 * @typedef {{combineUnits: boolean, maximized: boolean, preferredStyle: "lines"|"bars",
 *  chartStyles: Object<string, "lines"|"bars">, chartLevels: Object<string, ChartLevel>, shownMarkers: string[], shownEvents: string[],
 *  shownExtremes: ("min"|"max")[], shownOverlays: string[], showIncomplete: boolean, showValueTable: boolean, showComparisonLines: boolean, hideAxisValues: boolean, averagesOnly: boolean,
 *  dateRange: {from: string, to: string, preset?: string}|null, hiddenSeries: string[], entries: ChartEntry[]}} ChartSetup
 */

/** @return {ChartSetup} */
export function emptySetup() {
    // vertical lines are hidden by default, the dialog is maximized, two units share a chart with a second y-axis
    return {combineUnits: true, maximized: true, preferredStyle: "lines", chartStyles: {}, chartLevels: {}, shownMarkers: [], shownEvents: [], shownExtremes: [], shownOverlays: [], showIncomplete: true, showValueTable: true, showComparisonLines: false, hideAxisValues: false, averagesOnly: false, dateRange: null, hiddenSeries: [], entries: []};
}

const STYLES = ["lines", "bars"];
const EXTREMES = ["min", "max"];
const OVERLAYS = ["average1", "average3", "average12", "previousYear"];

function validExtremes(setup) {
    if (Array.isArray(setup.shownExtremes)) {
        return EXTREMES.filter(kind => setup.shownExtremes.includes(kind));
    }
    // the option "Minimum and maximum" was split into two options
    return setup.showExtremes === true ? [...EXTREMES] : [];
}
const WINDOWS = ["follow", "day", "week", "month", "quarter", "year", "calendar-month", "calendar-quarter", "calendar-year"];
const DETAILS = ["year", "month", "week", "day"];
const MARKER_KINDS = ["release", "price", "ide"];

/** @return {object} The entries of the map with valid values. */
function validValues(map, values) {
    if (map === null || typeof map !== "object") {
        return {};
    }
    return Object.fromEntries(Object.entries(map).filter(([key, value]) => typeof key === "string" && values.includes(value)));
}

const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/;

/** The ranges, which end today, e.g. the past 7 days, see RANGE_PRESETS in model.js. */
const RANGE_PRESETS = ["today", "past-7-days", "this-month"];

/**
 * @return {{from: string, to: string, preset?: string}|null} The stored date range of the line charts, null for all
 *  dates. A range, which ends today, has its preset, its dates are calculated again when it's shown.
 */
function validDateRange(range) {
    if (range === null || typeof range !== "object" || !ISO_DATE.test(range.from) || !ISO_DATE.test(range.to)) {
        return null;
    }
    if (range.from > range.to || isNaN(Date.parse(range.from)) || isNaN(Date.parse(range.to))) {
        return null;
    }
    return RANGE_PRESETS.includes(range.preset) ? {from: range.from, to: range.to, preset: range.preset} : {from: range.from, to: range.to};
}

/** @return {Object<string, ChartLevel>} The valid levels of the charts by the keys of the charts. */
function validLevels(levels) {
    if (levels === null || typeof levels !== "object") {
        return {};
    }
    return Object.fromEntries(Object.entries(levels)
        .filter(([, level]) => level !== null && typeof level === "object" && WINDOWS.includes(level.window) && DETAILS.includes(level.detail))
        .map(([key, level]) => [key, {window: level.window, detail: level.detail}]));
}

function isValidMetricEntry(entry) {
    return typeof entry.metric === "string"
        && typeof entry.label === "string"
        && typeof entry.unit === "string"
        && (entry.window === undefined || WINDOWS.includes(entry.window))
        && (entry.usesWindow === undefined || typeof entry.usesWindow === "boolean")
        && (entry.average === undefined || [1, 3, 12].includes(entry.average))
        && (entry.measure === undefined || typeof entry.measure === "string")
        && (entry.color === undefined || typeof entry.color === "string")
        && (entry.style === undefined || STYLES.includes(entry.style))
        && (entry.row === undefined || typeof entry.row === "string");
}

function isValidEntry(entry) {
    if (entry !== null && typeof entry === "object" && entry.type === "metric") {
        return isValidMetricEntry(entry);
    }
    return entry !== null && typeof entry === "object"
        && (entry.type === "line" || entry.type === "pie")
        && typeof entry.table === "string"
        && typeof entry.column === "string"
        && (entry.color === undefined || typeof entry.color === "string")
        && (entry.style === undefined || STYLES.includes(entry.style))
        && (entry.row === undefined || typeof entry.row === "string");
}

/**
 * @param {object|null} setup A stored setup, e.g. of the local storage or of a bookmark.
 * @return {ChartSetup|null} The setup with the valid values, or null if it isn't a setup of this version.
 */
function parseSetup(setup) {
    if (setup === null || typeof setup !== "object" || !READ_VERSIONS.includes(setup.version) || !Array.isArray(setup.entries)) {
        return null;
    }
    return {
        // a second y-axis is used by default
        combineUnits: setup.combineUnits !== false,
        maximized: setup.maximized !== false,
        preferredStyle: STYLES.includes(setup.preferredStyle) ? setup.preferredStyle : "lines",
        chartStyles: validValues(setup.chartStyles, STYLES),
        // the levels of version 1 were the levels of the tables, they don't apply to the charts of metrics
        chartLevels: setup.version === VERSION ? validLevels(setup.chartLevels) : {},
        shownMarkers: Array.isArray(setup.shownMarkers) ? setup.shownMarkers.filter(kind => MARKER_KINDS.includes(kind)) : [],
        // the IDs of the shown custom events, also of events of other pages
        shownEvents: Array.isArray(setup.shownEvents) ? setup.shownEvents.filter(id => typeof id === "string") : [],
        shownExtremes: validExtremes(setup),
        shownOverlays: Array.isArray(setup.shownOverlays) ? OVERLAYS.filter(kind => setup.shownOverlays.includes(kind)) : [],
        // shown by default, also with a setup stored before the option existed
        showIncomplete: setup.showIncomplete !== false,
        showValueTable: setup.showValueTable !== false,
        // hidden by default, also with a setup stored before the option existed
        showComparisonLines: setup.showComparisonLines === true,
        hideAxisValues: setup.hideAxisValues === true,
        averagesOnly: setup.averagesOnly === true,
        dateRange: validDateRange(setup.dateRange ?? null),
        // the keys of the series and overlays, which are hidden by a click on the legend
        hiddenSeries: Array.isArray(setup.hiddenSeries) ? setup.hiddenSeries.filter(key => typeof key === "string") : [],
        entries: setup.entries.filter(isValidEntry),
    };
}

/** The prefix of the fragment of the address of a bookmarked view of the charts, e.g. "#charts=eyJ2Z…". */
export const VIEW_FRAGMENT_PREFIX = "charts=";

/**
 * @param {ChartSetup} setup
 * @return {string} The fragment of the address of a bookmark of the view, without "#". The setup is JSON as base64url.
 */
export function encodeView(setup) {
    const bytes = new TextEncoder().encode(JSON.stringify({version: VERSION, ...setup}));
    const base64 = btoa(String.fromCharCode(...bytes));
    return VIEW_FRAGMENT_PREFIX + base64.replaceAll("+", "-").replaceAll("/", "_").replace(/=+$/, "");
}

/**
 * @param {string} fragment The fragment of the address, with or without "#".
 * @return {ChartSetup|null} The setup of a bookmarked view, or null if the fragment isn't a valid view.
 */
export function decodeView(fragment) {
    const text = fragment.replace(/^#/, "");
    if (!text.startsWith(VIEW_FRAGMENT_PREFIX)) {
        return null;
    }
    try {
        const base64 = text.substring(VIEW_FRAGMENT_PREFIX.length).replaceAll("-", "+").replaceAll("_", "/");
        const bytes = Uint8Array.from(atob(base64), char => char.charCodeAt(0));
        return parseSetup(JSON.parse(new TextDecoder().decode(bytes)));
    } catch (e) {
        return null;
    }
}

/**
 * @param {Storage|null} storage The local storage, or null if it's unavailable.
 */
export function createSetupStorage(storage) {
    return {
        /** @return {ChartSetup} The stored setup, or an empty setup if there is none or it's invalid. */
        load() {
            try {
                return parseSetup(JSON.parse(storage?.getItem(STORAGE_KEY) ?? "null")) ?? emptySetup();
            } catch (e) {
                // invalid data or a storage, which can't be accessed
                return emptySetup();
            }
        },

        /** @param {ChartSetup} setup */
        save(setup) {
            try {
                storage?.setItem(STORAGE_KEY, JSON.stringify({version: VERSION, ...setup}));
            } catch (e) {
                // the setup is just not stored if the storage is full or can't be accessed, e.g. in a private window
            }
        },
    };
}

/**
 * @return {Storage|null} The local storage, or null if the browser denies access.
 */
export function browserStorage() {
    try {
        return window.localStorage;
    } catch (e) {
        return null;
    }
}
