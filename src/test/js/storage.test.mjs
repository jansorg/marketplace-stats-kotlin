/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

import {test} from "node:test";
import assert from "node:assert/strict";
import {createSetupStorage, decodeView, emptySetup, encodeView, STORAGE_KEY} from "../../main/resources/js/charts/storage.js";

function memoryStorage(initial = {}) {
    const values = new Map(Object.entries(initial));
    return {getItem: key => values.get(key) ?? null, setItem: (key, value) => values.set(key, value), values};
}

test("a stored setup is loaded", () => {
    const storage = memoryStorage();
    const setupStorage = createSetupStorage(storage);
    const setup = {
        combineUnits: true, averagesOnly: true, maximized: false, preferredStyle: "bars", chartStyles: {"month/USD": "bars"}, granularities: {"month/USD": "day"}, shownMarkers: ["ide"], shownEvents: ["price-announcement"], shownExtremes: ["max"], shownOverlays: ["average12", "previousYear"], showIncomplete: false, showValueTable: false,
        dateRange: {from: "2024-01-01", to: "2024-12-31"}, hiddenSeries: ["line/Overview/MRR", "line/Overview/MRR:average3"],
        entries: [{type: "line", table: "Overview", column: "MRR", color: "#2a78d6"}, {type: "pie", table: "Top Countries", column: "% of Sales"}],
    };
    setupStorage.save(setup);
    assert.deepEqual(setupStorage.load(), setup);
    // only the setup is stored, no values of the tables
    assert.deepEqual(Object.keys(JSON.parse(storage.values.get(STORAGE_KEY))).sort(),
        ["averagesOnly", "chartStyles", "combineUnits", "dateRange", "entries", "granularities", "hiddenSeries", "maximized", "preferredStyle", "showIncomplete", "showValueTable", "shownEvents", "shownExtremes", "shownMarkers", "shownOverlays", "version"]);
});

test("the dialog is maximized by default", () => {
    const load = stored => createSetupStorage(memoryStorage({[STORAGE_KEY]: JSON.stringify({version: 1, entries: [], ...stored})})).load();
    assert.equal(emptySetup().maximized, true);
    assert.equal(load({}).maximized, true);
    assert.equal(load({maximized: false}).maximized, false);
});

test("invalid data is ignored", () => {
    assert.deepEqual(createSetupStorage(memoryStorage({[STORAGE_KEY]: "{invalid"})).load(), emptySetup());
    assert.deepEqual(createSetupStorage(memoryStorage({[STORAGE_KEY]: JSON.stringify({version: 99, entries: []})})).load(), emptySetup());

    const stored = {
        version: 1, preferredStyle: "pie", chartStyles: {"a": "bars", "b": "dots"}, granularities: {"a": "week", "b": "hour"}, shownMarkers: ["ide", "unknown", 1],
        entries: [{type: "line", table: "Overview", column: "MRR"}, {type: "unknown", table: "X", column: "Y"}, {type: "pie", table: 1, column: "Y"}],
    };
    const setup = createSetupStorage(memoryStorage({[STORAGE_KEY]: JSON.stringify(stored)})).load();
    assert.equal(setup.preferredStyle, "lines");
    assert.deepEqual(setup.chartStyles, {"a": "bars"});
    assert.deepEqual(setup.granularities, {"a": "week"});
    assert.deepEqual(setup.shownMarkers, ["ide"]);
    assert.deepEqual(setup.entries, [{type: "line", table: "Overview", column: "MRR"}]);
});

test("the stored option of the minimum and maximum is shown as two options", () => {
    const load = stored => createSetupStorage(memoryStorage({[STORAGE_KEY]: JSON.stringify({version: 1, entries: [], ...stored})})).load();
    assert.deepEqual(load({showExtremes: true}).shownExtremes, ["min", "max"]);
    assert.deepEqual(load({showExtremes: false}).shownExtremes, []);
    assert.deepEqual(load({shownExtremes: ["max", "other"]}).shownExtremes, ["max"]);
});

test("an unavailable storage doesn't fail", () => {
    const failing = {getItem: () => { throw new Error("denied"); }, setItem: () => { throw new Error("full"); }};
    assert.deepEqual(createSetupStorage(failing).load(), emptySetup());
    assert.doesNotThrow(() => createSetupStorage(failing).save(emptySetup()));
    assert.deepEqual(createSetupStorage(null).load(), emptySetup());
});

test("a bookmarked view is the setup in the fragment of the address", () => {
    const setup = {
        ...emptySetup(), granularities: {"year/USD": "monthEnd"}, shownMarkers: ["release"], dateRange: {from: "2025-03-01", to: "2025-06-30"},
        entries: [{type: "line", table: "Years", column: "Sales Total", color: "#2a78d6"}, {type: "pie", table: "Top Countries", column: "% of Sales"}],
    };
    const fragment = encodeView(setup);
    assert.match(fragment, /^charts=[A-Za-z0-9_-]+$/);
    assert.deepEqual(decodeView(fragment), setup);
    assert.deepEqual(decodeView("#" + fragment), setup);
    // non-ASCII names of tables and columns
    const named = {...emptySetup(), entries: [{type: "line", table: "Übersicht", column: "Umsatz €"}]};
    assert.deepEqual(decodeView(encodeView(named)), named);
});

test("invalid keys of hidden lines are ignored", () => {
    const load = hiddenSeries => createSetupStorage(memoryStorage({[STORAGE_KEY]: JSON.stringify({version: 1, entries: [], hiddenSeries})})).load().hiddenSeries;
    assert.deepEqual(load(undefined), []);
    assert.deepEqual(load("line/Overview/MRR"), []);
    assert.deepEqual(load(["line/Overview/MRR", 3, null]), ["line/Overview/MRR"]);
});

test("an invalid date range shows all dates", () => {
    const load = dateRange => createSetupStorage(memoryStorage({[STORAGE_KEY]: JSON.stringify({version: 1, entries: [], dateRange})})).load().dateRange;
    assert.equal(load(undefined), null);
    assert.equal(load("2025"), null);
    assert.equal(load({from: "2025-01-01"}), null);
    assert.equal(load({from: "2025-13-45", to: "2025-12-31"}), null);
    // the end before the start
    assert.equal(load({from: "2025-12-31", to: "2025-01-01"}), null);
    assert.deepEqual(load({from: "2025-01-01", to: "2025-01-01", other: 1}), {from: "2025-01-01", to: "2025-01-01"});
});

test("an invalid bookmarked view is ignored", () => {
    assert.equal(decodeView(""), null);
    assert.equal(decodeView("#other"), null);
    assert.equal(decodeView("#charts=not-base64!"), null);
    assert.equal(decodeView("#charts=" + btoa(JSON.stringify({version: 99, entries: []}))), null);
});

test("a series of a row of a table is stored with the name of the row", () => {
    const storage = createSetupStorage(memoryStorage());
    const setup = {...emptySetup(), entries: [{type: "line", table: "Customer Type", column: "% of Sales", row: "Individual", color: "#2a78d6"}]};
    storage.save(setup);
    assert.deepEqual(storage.load(), setup);
    assert.deepEqual(createSetupStorage(memoryStorage({[STORAGE_KEY]: JSON.stringify({version: 1, entries: [{type: "line", table: "T", column: "C", row: 1}]})})).load().entries, []);
});
