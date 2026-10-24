/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

import {test} from "node:test";
import assert from "node:assert/strict";
import {createSetupStorage, emptySetup, STORAGE_KEY} from "../../main/resources/js/charts/storage.js";

function memoryStorage(initial = {}) {
    const values = new Map(Object.entries(initial));
    return {getItem: key => values.get(key) ?? null, setItem: (key, value) => values.set(key, value), values};
}

test("a stored setup is loaded", () => {
    const storage = memoryStorage();
    const setupStorage = createSetupStorage(storage);
    const setup = {
        combineUnits: true, maximized: false, preferredStyle: "bars", chartStyles: {"month/USD": "bars"}, granularities: {"month/USD": "day"}, shownMarkers: ["ide"],
        entries: [{type: "line", table: "Overview", column: "MRR", color: "#2a78d6"}, {type: "pie", table: "Top Countries", column: "% of Sales"}],
    };
    setupStorage.save(setup);
    assert.deepEqual(setupStorage.load(), setup);
    // only the setup is stored, no values of the tables
    assert.deepEqual(Object.keys(JSON.parse(storage.values.get(STORAGE_KEY))).sort(),
        ["chartStyles", "combineUnits", "entries", "granularities", "maximized", "preferredStyle", "shownMarkers", "version"]);
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

test("an unavailable storage doesn't fail", () => {
    const failing = {getItem: () => { throw new Error("denied"); }, setItem: () => { throw new Error("full"); }};
    assert.deepEqual(createSetupStorage(failing).load(), emptySetup());
    assert.doesNotThrow(() => createSetupStorage(failing).save(emptySetup()));
    assert.deepEqual(createSetupStorage(null).load(), emptySetup());
});
