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
 *  chartStyles: Object<string, "lines"|"bars">, shownMarkers: string[], entries: ChartEntry[]}} ChartSetup
 */

/** @return {ChartSetup} */
export function emptySetup() {
    // vertical lines are hidden by default
    return {combineUnits: false, maximized: false, preferredStyle: "lines", chartStyles: {}, shownMarkers: [], entries: []};
}

const STYLES = ["lines", "bars"];
const MARKER_KINDS = ["release", "price", "ide"];

function validStyles(chartStyles) {
    if (chartStyles === null || typeof chartStyles !== "object") {
        return {};
    }
    return Object.fromEntries(Object.entries(chartStyles).filter(([key, style]) => typeof key === "string" && STYLES.includes(style)));
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
                    chartStyles: validStyles(setup.chartStyles),
                    shownMarkers: Array.isArray(setup.shownMarkers) ? setup.shownMarkers.filter(kind => MARKER_KINDS.includes(kind)) : [],
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
