/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

import {test} from "node:test";
import assert from "node:assert/strict";
import {createSetupStorage, decodeView, emptySetup, encodeView, setupStorageKey, STORAGE_KEY} from "../../main/resources/js/charts/storage.js";

function memoryStorage(initial = {}) {
    const values = new Map(Object.entries(initial));
    return {getItem: key => values.get(key) ?? null, setItem: (key, value) => values.set(key, value), values};
}

test("a stored setup is loaded", () => {
    const storage = memoryStorage();
    const setupStorage = createSetupStorage(storage);
    const setup = {
        combineUnits: true, maximized: false, preferredStyle: "bars", chartStyles: {"month/USD": "bars"}, chartLevels: {"metric/USD": {window: "year", detail: "day"}}, shownMarkers: ["ide"], shownEvents: ["price-announcement"], shownExtremes: ["max"], shownOverlays: ["average12", "previousYear"], showIncomplete: false, showValueTable: false, showComparisonLines: true, hideAxisValues: true, logScale: true,
        dateRange: {from: "2024-01-01", to: "2024-12-31"}, hiddenSeries: ["line/Overview/MRR", "line/Overview/MRR:average3"],
        entries: [
            {type: "metric", metric: "mrr", usesWindow: false, label: "MRR", unit: "USD", measure: "stock", color: "#2a78d6"},
            {type: "metric", metric: "trials", window: "day", usesWindow: true, label: "Trials", unit: "count", measure: "flow", color: "#1baf7a"},
            {type: "line", table: "Renewal Rates", column: "All", color: "#eb6834"},
            {type: "pie", table: "Top Countries", column: "% of Sales"},
        ],
    };
    setupStorage.save(setup);
    assert.deepEqual(setupStorage.load(), setup);
    // only the setup is stored, no values of the tables
    assert.deepEqual(Object.keys(JSON.parse(storage.values.get(setupStorageKey(null)))).sort(),
        ["chartLevels", "chartStyles", "combineUnits", "dateRange", "entries", "hiddenSeries", "hideAxisValues", "logScale", "maximized", "preferredStyle", "showComparisonLines", "showIncomplete", "showValueTable", "shownEvents", "shownExtremes", "shownMarkers", "shownOverlays", "version"]);
});

test("the dialog is maximized by default", () => {
    const load = stored => createSetupStorage(memoryStorage({[setupStorageKey(null)]: JSON.stringify({version: 2, entries: [], ...stored})})).load();
    assert.equal(emptySetup().maximized, true);
    assert.equal(load({}).maximized, true);
    assert.equal(load({maximized: false}).maximized, false);
});

test("the second y-axis is used by default", () => {
    const load = stored => createSetupStorage(memoryStorage({[setupStorageKey(null)]: JSON.stringify({version: 2, entries: [], ...stored})})).load();
    assert.equal(emptySetup().combineUnits, true);
    assert.equal(load({}).combineUnits, true);
    assert.equal(load({combineUnits: false}).combineUnits, false);
});

test("the comparison lines are hidden by default", () => {
    const load = stored => createSetupStorage(memoryStorage({[setupStorageKey(null)]: JSON.stringify({version: 2, entries: [], ...stored})})).load();
    assert.equal(emptySetup().showComparisonLines, false);
    assert.equal(load({}).showComparisonLines, false);
    assert.equal(load({showComparisonLines: true}).showComparisonLines, true);
});

test("invalid data is ignored", () => {
    assert.deepEqual(createSetupStorage(memoryStorage({[setupStorageKey(null)]: "{invalid"})).load(), emptySetup());
    assert.deepEqual(createSetupStorage(memoryStorage({[setupStorageKey(null)]: JSON.stringify({version: 99, entries: []})})).load(), emptySetup());

    const stored = {
        version: 2, preferredStyle: "pie", chartStyles: {"a": "bars", "b": "dots"}, shownMarkers: ["ide", "unknown", 1],
        chartLevels: {"a": {window: "year", detail: "week"}, "b": {window: "hour", detail: "day"}, "c": {window: "month"}},
        entries: [{type: "line", table: "Overview", column: "MRR"}, {type: "unknown", table: "X", column: "Y"}, {type: "pie", table: 1, column: "Y"},
            {type: "metric", metric: "mrr", label: "MRR"}, {type: "metric", metric: "trials", window: "hour", label: "Trials", unit: "count"}],
    };
    const setup = createSetupStorage(memoryStorage({[setupStorageKey(null)]: JSON.stringify(stored)})).load();
    assert.equal(setup.preferredStyle, "lines");
    assert.deepEqual(setup.chartStyles, {"a": "bars"});
    assert.deepEqual(setup.chartLevels, {"a": {window: "year", detail: "week"}});
    assert.deepEqual(setup.shownMarkers, ["ide"]);
    assert.deepEqual(setup.entries, [{type: "line", table: "Overview", column: "MRR"}]);
});

test("only the known kinds of extremes are shown", () => {
    const load = stored => createSetupStorage(memoryStorage({[setupStorageKey(null)]: JSON.stringify({version: 2, entries: [], ...stored})})).load();
    assert.deepEqual(load({}).shownExtremes, []);
    assert.deepEqual(load({shownExtremes: "max"}).shownExtremes, []);
    assert.deepEqual(load({shownExtremes: ["max", "other", "min"]}).shownExtremes, ["min", "max"]);
});

test("a setup of version 1 is ignored", () => {
    const stored = {version: 1, entries: [{type: "line", table: "Overview", column: "MRR", color: "#2a78d6"}]};
    assert.deepEqual(createSetupStorage(memoryStorage({[setupStorageKey(null)]: JSON.stringify(stored)})).load(), emptySetup());
    // a bookmarked view of version 1
    const base64 = btoa(JSON.stringify(stored)).replaceAll("+", "-").replaceAll("/", "_").replace(/=+$/, "");
    assert.equal(decodeView("#charts=" + base64), null);
});

test("an unavailable storage doesn't fail", () => {
    const failing = {getItem: () => { throw new Error("denied"); }, setItem: () => { throw new Error("full"); }};
    assert.deepEqual(createSetupStorage(failing).load(), emptySetup());
    assert.doesNotThrow(() => createSetupStorage(failing).save(emptySetup()));
    assert.deepEqual(createSetupStorage(null).load(), emptySetup());
});

test("a bookmarked view is the setup in the fragment of the address", () => {
    const setup = {
        ...emptySetup(), chartLevels: {"metric/USD": {window: "year", detail: "month"}}, shownMarkers: ["release"], dateRange: {from: "2025-03-01", to: "2025-06-30"},
        entries: [{type: "line", table: "Years", column: "Sales Total", color: "#2a78d6"}, {type: "pie", table: "Top Countries", column: "% of Sales"}],
    };
    const fragment = encodeView(setup);
    assert.match(fragment, /^charts=[A-Za-z0-9_-]+$/);
    assert.deepEqual(decodeView(fragment), setup);
    assert.deepEqual(decodeView("#" + fragment), setup);
    // non-ASCII names of tables and columns
    const named = {...emptySetup(), entries: [{type: "line", table: "Übersicht", column: "Umsatz €"}]};
    assert.deepEqual(decodeView(encodeView(named)), named);
    // a long view, e.g. with many hidden lines, is encoded, too
    const long = {...emptySetup(), hiddenSeries: Array.from({length: 20000}, (_, i) => `line/Overview/Column ${i}`)};
    assert.deepEqual(decodeView(encodeView(long)), long);
});

test("invalid keys of hidden lines are ignored", () => {
    const load = hiddenSeries => createSetupStorage(memoryStorage({[setupStorageKey(null)]: JSON.stringify({version: 2, entries: [], hiddenSeries})})).load().hiddenSeries;
    assert.deepEqual(load(undefined), []);
    assert.deepEqual(load("line/Overview/MRR"), []);
    assert.deepEqual(load(["line/Overview/MRR", 3, null]), ["line/Overview/MRR"]);
});

test("an invalid date range shows all dates", () => {
    const load = dateRange => createSetupStorage(memoryStorage({[setupStorageKey(null)]: JSON.stringify({version: 2, entries: [], dateRange})})).load().dateRange;
    assert.equal(load(undefined), null);
    assert.equal(load("2025"), null);
    assert.equal(load({from: "2025-01-01"}), null);
    assert.equal(load({from: "2025-13-45", to: "2025-12-31"}), null);
    // days, which don't exist, aren't moved to the next month
    assert.equal(load({from: "2025-02-29", to: "2025-12-31"}), null);
    assert.equal(load({from: "2025-01-01", to: "2025-04-31"}), null);
    assert.deepEqual(load({from: "2024-02-29", to: "2024-03-31"}), {from: "2024-02-29", to: "2024-03-31"});
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
    assert.deepEqual(createSetupStorage(memoryStorage({[setupStorageKey(null)]: JSON.stringify({version: 2, entries: [{type: "line", table: "T", column: "C", row: 1}]})})).load().entries, []);
});

test("a line is stored with its style, lines with an invalid style are ignored", () => {
    const storage = createSetupStorage(memoryStorage());
    const entries = [
        {type: "metric", metric: "sales", window: "calendar-month", usesWindow: true, label: "Sales", unit: "USD", style: "bars"},
        {type: "line", table: "Overview", column: "MRR", style: "lines"},
    ];
    storage.save({...emptySetup(), entries});
    assert.deepEqual(storage.load().entries, entries);
    storage.save({...emptySetup(), entries: [{...entries[0], style: "area"}]});
    assert.deepEqual(storage.load().entries, []);
});

test("a range until today is stored with its preset, an unknown preset is ignored", () => {
    const storage = createSetupStorage(memoryStorage());
    const dateRange = {from: "2026-10-20", to: "2026-10-26", preset: "past-7-days"};
    storage.save({...emptySetup(), dateRange});
    assert.deepEqual(storage.load().dateRange, dateRange);
    storage.save({...emptySetup(), dateRange: {...dateRange, preset: "yesterday"}});
    assert.deepEqual(storage.load().dateRange, {from: "2026-10-20", to: "2026-10-26"});
});

const salesEntry = {type: "metric", metric: "sales", label: "Sales", unit: "USD"};
const trialsEntry = {type: "metric", metric: "trials", label: "Trials", unit: "count"};

test("each plugin and the summary of all plugins have their own key", () => {
    assert.equal(setupStorageKey("1"), "marketplace-stats.charts.plugin-1");
    assert.equal(setupStorageKey(2), "marketplace-stats.charts.plugin-2");
    assert.equal(setupStorageKey(null), "marketplace-stats.charts.all-plugins");
    assert.equal(setupStorageKey(undefined), setupStorageKey(null));
    assert.equal(setupStorageKey(""), setupStorageKey(null));
    assert.notEqual(setupStorageKey("1"), STORAGE_KEY);
});

test("the setup of a plugin doesn't change the setups of other plugins", () => {
    const storage = memoryStorage();
    const first = createSetupStorage(storage, "1");
    const second = createSetupStorage(storage, "2");
    const summary = createSetupStorage(storage, null);
    first.save({...emptySetup(), entries: [salesEntry]});
    second.save({...emptySetup(), entries: [trialsEntry]});
    summary.save({...emptySetup(), preferredStyle: "bars", entries: [salesEntry, trialsEntry]});

    // removing all charts of a plugin stores an empty setup of this plugin
    first.save(emptySetup());
    assert.deepEqual(first.load(), emptySetup());
    assert.deepEqual(second.load().entries, [trialsEntry]);
    assert.deepEqual(summary.load().entries, [salesEntry, trialsEntry]);
    assert.equal(summary.load().preferredStyle, "bars");
    // all pages of a plugin share its setup
    assert.deepEqual(createSetupStorage(storage, "2").load().entries, [trialsEntry]);
});

test("the setup, which was shared by all plugins before, is ignored", () => {
    const storage = memoryStorage({[STORAGE_KEY]: JSON.stringify({version: 2, ...emptySetup(), entries: [salesEntry]})});
    assert.deepEqual(createSetupStorage(storage, "1").load(), emptySetup());
    assert.deepEqual(createSetupStorage(storage, null).load(), emptySetup());
});

test("a bookmarked view is stored as the setup of the plugin of the page", () => {
    const storage = memoryStorage();
    createSetupStorage(storage, "2").save({...emptySetup(), entries: [trialsEntry]});
    const view = decodeView("#" + encodeView({...emptySetup(), entries: [salesEntry]}));
    createSetupStorage(storage, "1").save(view);
    assert.deepEqual(createSetupStorage(storage, "1").load().entries, [salesEntry]);
    assert.deepEqual(createSetupStorage(storage, "2").load().entries, [trialsEntry]);
});

test("the y-axes are linear by default, also with a setup stored before the option existed", () => {
    const load = stored => createSetupStorage(memoryStorage({[setupStorageKey(null)]: JSON.stringify({version: 2, entries: [], ...stored})})).load();
    assert.equal(emptySetup().logScale, false);
    assert.equal(load({}).logScale, false);
    assert.equal(load({logScale: "yes"}).logScale, false);
    assert.equal(load({logScale: true}).logScale, true);
});

test("a copy of a line is stored with its number, an invalid number is ignored", () => {
    const entry = {type: "metric", metric: "release-downloads.1", label: "Downloads", unit: "count"};
    const view = setup => decodeView(encodeView({...emptySetup(), entries: setup}));
    assert.deepEqual(view([entry, {...entry, copy: 2}]).entries.map(e => e.copy), [undefined, 2]);
    assert.equal(view([{...entry, copy: 1}, {...entry, copy: "2"}]).entries.length, 0);
});
