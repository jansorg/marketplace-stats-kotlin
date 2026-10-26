/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

/**
 * Persistence of the chart setup in the local storage of the browser.
 * Only the selected columns and the options are stored, never the values of the tables.
 */

export const STORAGE_KEY = "marketplace-stats.charts";
const VERSION = 1;

/**
 * @typedef {{type: "line"|"pie", table: string, column: string, row?: string, color?: string}} ChartEntry
 * @typedef {{combineUnits: boolean, maximized: boolean, preferredStyle: "lines"|"bars",
 *  chartStyles: Object<string, "lines"|"bars">, granularities: Object<string, "yearEnd"|"month"|"monthEnd"|"week"|"day">, shownMarkers: string[], shownEvents: string[],
 *  shownExtremes: ("min"|"max")[], shownOverlays: string[], showIncomplete: boolean, showValueTable: boolean,
 *  entries: ChartEntry[]}} ChartSetup
 */

/** @return {ChartSetup} */
export function emptySetup() {
    // vertical lines are hidden by default, the dialog is maximized
    return {combineUnits: false, maximized: true, preferredStyle: "lines", chartStyles: {}, granularities: {}, shownMarkers: [], shownEvents: [], shownExtremes: [], shownOverlays: [], showIncomplete: true, showValueTable: true, entries: []};
}

const STYLES = ["lines", "bars"];
const EXTREMES = ["min", "max"];
const OVERLAYS = ["average3", "average12", "previousYear"];

function validExtremes(setup) {
    if (Array.isArray(setup.shownExtremes)) {
        return EXTREMES.filter(kind => setup.shownExtremes.includes(kind));
    }
    // the option "Minimum and maximum" was split into two options
    return setup.showExtremes === true ? [...EXTREMES] : [];
}
const GRANULARITIES = ["yearEnd", "month", "monthEnd", "week", "day"];
const MARKER_KINDS = ["release", "price", "ide"];

/** @return {object} The entries of the map with valid values. */
function validValues(map, values) {
    if (map === null || typeof map !== "object") {
        return {};
    }
    return Object.fromEntries(Object.entries(map).filter(([key, value]) => typeof key === "string" && values.includes(value)));
}

function isValidEntry(entry) {
    return entry !== null && typeof entry === "object"
        && (entry.type === "line" || entry.type === "pie")
        && typeof entry.table === "string"
        && typeof entry.column === "string"
        && (entry.color === undefined || typeof entry.color === "string")
        && (entry.row === undefined || typeof entry.row === "string");
}

/**
 * @param {object|null} setup A stored setup, e.g. of the local storage or of a bookmark.
 * @return {ChartSetup|null} The setup with the valid values, or null if it isn't a setup of this version.
 */
function parseSetup(setup) {
    if (setup === null || typeof setup !== "object" || setup.version !== VERSION || !Array.isArray(setup.entries)) {
        return null;
    }
    return {
        combineUnits: setup.combineUnits === true,
        maximized: setup.maximized !== false,
        preferredStyle: STYLES.includes(setup.preferredStyle) ? setup.preferredStyle : "lines",
        chartStyles: validValues(setup.chartStyles, STYLES),
        granularities: validValues(setup.granularities, GRANULARITIES),
        shownMarkers: Array.isArray(setup.shownMarkers) ? setup.shownMarkers.filter(kind => MARKER_KINDS.includes(kind)) : [],
        // the IDs of the shown custom events, also of events of other pages
        shownEvents: Array.isArray(setup.shownEvents) ? setup.shownEvents.filter(id => typeof id === "string") : [],
        shownExtremes: validExtremes(setup),
        shownOverlays: Array.isArray(setup.shownOverlays) ? OVERLAYS.filter(kind => setup.shownOverlays.includes(kind)) : [],
        // shown by default, also with a setup stored before the option existed
        showIncomplete: setup.showIncomplete !== false,
        showValueTable: setup.showValueTable !== false,
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
