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
 * @typedef {{type: "line"|"pie", table: string, column: string, color?: string}} ChartEntry
 * @typedef {{combineUnits: boolean, maximized: boolean, preferredStyle: "lines"|"bars",
 *  chartStyles: Object<string, "lines"|"bars">, granularities: Object<string, "month"|"week"|"day">, shownMarkers: string[],
 *  shownExtremes: ("min"|"max")[], showIncomplete: boolean, showValueTable: boolean, entries: ChartEntry[]}} ChartSetup
 */

/** @return {ChartSetup} */
export function emptySetup() {
    // vertical lines are hidden by default
    return {combineUnits: false, maximized: false, preferredStyle: "lines", chartStyles: {}, granularities: {}, shownMarkers: [], shownExtremes: [], showIncomplete: true, showValueTable: true, entries: []};
}

const STYLES = ["lines", "bars"];
const EXTREMES = ["min", "max"];

function validExtremes(setup) {
    if (Array.isArray(setup.shownExtremes)) {
        return EXTREMES.filter(kind => setup.shownExtremes.includes(kind));
    }
    // the option "Minimum and maximum" was split into two options
    return setup.showExtremes === true ? [...EXTREMES] : [];
}
const GRANULARITIES = ["month", "week", "day"];
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
        && (entry.color === undefined || typeof entry.color === "string");
}

/**
 * @param {Storage|null} storage The local storage, or null if it's unavailable.
 */
export function createSetupStorage(storage) {
    return {
        /** @return {ChartSetup} The stored setup, or an empty setup if there is none or it's invalid. */
        load() {
            try {
                const setup = JSON.parse(storage?.getItem(STORAGE_KEY) ?? "null");
                if (setup === null || setup.version !== VERSION || !Array.isArray(setup.entries)) {
                    return emptySetup();
                }
                return {
                    combineUnits: setup.combineUnits === true,
                    maximized: setup.maximized === true,
                    preferredStyle: STYLES.includes(setup.preferredStyle) ? setup.preferredStyle : "lines",
                    chartStyles: validValues(setup.chartStyles, STYLES),
                    granularities: validValues(setup.granularities, GRANULARITIES),
                    shownMarkers: Array.isArray(setup.shownMarkers) ? setup.shownMarkers.filter(kind => MARKER_KINDS.includes(kind)) : [],
                    shownExtremes: validExtremes(setup),
                    // shown by default, also with a setup stored before the option existed
                    showIncomplete: setup.showIncomplete !== false,
                    showValueTable: setup.showValueTable !== false,
                    entries: setup.entries.filter(isValidEntry),
                };
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
