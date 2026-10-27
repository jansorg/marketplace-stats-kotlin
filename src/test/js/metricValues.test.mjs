/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

import {test} from "node:test";
import assert from "node:assert/strict";
import {metricDataId, metricEntry, metricSeries, MetricValueCache} from "../../main/resources/js/charts/metricValues.js";

/** @return A cache, whose requests are answered by `respond`, and the list of its requests. */
function cache(respond) {
    const requests = [];
    const values = new MetricValueCache(async (window, detail, ids) => {
        requests.push({window, detail, ids});
        return respond(ids);
    });
    return {values, requests};
}

const response = (dates, ids) => ({dates, series: Object.fromEntries(ids.map(id => [id, {values: dates.map(() => 1), unit: "USD"}]))});

test("only the missing values are requested", async () => {
    const {values, requests} = cache(ids => response(["2026-01-31"], ids));
    await values.load("calendar-month", "month", ["sales", "sales", "mrr"]);
    assert.deepEqual(requests, [{window: "calendar-month", detail: "month", ids: ["sales", "mrr"]}]);
    assert.equal(values.load("calendar-month", "month", ["sales"]), null);
    await values.load("calendar-month", "month", ["sales", "trials"]);
    assert.deepEqual(requests[1].ids, ["trials"]);
    // the values of the same dates are kept
    assert.deepEqual(Object.keys(values.data("calendar-month", "month").series).sort(), ["mrr", "sales", "trials"]);
    // other levels have their own values
    assert.equal(values.data("calendar-year", "year"), null);
});

test("the values of other dates replace the loaded values", async () => {
    let dates = ["2026-01-31"];
    const {values} = cache(ids => response(dates, ids));
    await values.load("day", "day", ["sales"]);
    dates = ["2026-01-31", "2026-02-01"];
    await values.load("day", "day", ["mrr"]);
    assert.deepEqual(Object.keys(values.data("day", "day").series), ["mrr"]);
});

test("metrics without values aren't requested again", async () => {
    const {values, requests} = cache(() => response(["2026-01-31"], []));
    const {data, requested} = await values.load("day", "day", ["trials"]);
    assert.deepEqual(requested, ["trials"]);
    assert.deepEqual(data.series, {});
    assert.equal(values.load("day", "day", ["trials"]), null);
    assert.equal(requests.length, 1);
});

test("a level has one request at a time", async () => {
    const {values, requests} = cache(ids => response(["2026-01-31"], ids));
    const first = values.load("day", "day", ["sales"]);
    assert.equal(values.isLoading, true);
    assert.equal(values.load("day", "day", ["mrr"]), null);
    await first;
    assert.equal(values.isLoading, false);
    assert.equal(requests.length, 1);
});

test("values, which failed to load, are requested again after the current task", async () => {
    let fail = true;
    const {values, requests} = cache(ids => {
        if (fail) {
            throw new Error("unavailable");
        }
        return response(["2026-01-31"], ids);
    });
    await assert.rejects(values.load("day", "day", ["sales"]), /unavailable/);
    assert.equal(values.isLoading, false);
    // e.g. the charts, which are drawn again after the error, don't request them again immediately
    assert.equal(values.load("day", "day", ["sales"]), null);
    await new Promise(resolve => setTimeout(resolve));
    fail = false;
    await values.load("day", "day", ["sales"]);
    assert.equal(requests.length, 2);
    assert.ok(values.data("day", "day").series.sales);
});

test("series of metrics and their stored entries", () => {
    const sales = metricSeries({metric: "sales", window: "year", label: "Sales", unit: "USD", measure: "flow", average: 3}, "#2a78d6");
    assert.equal(sales.key, "metric:sales@year~avg3");
    assert.equal(sales.usesWindow, true);
    assert.equal(metricDataId(sales), "sales@avg3");
    // values at a date have no window
    const mrr = metricSeries({metric: "mrr", window: "year", label: "MRR", unit: "USD", measure: "stock"}, "#eb6834");
    assert.equal(mrr.window, null);
    assert.equal(metricDataId(mrr), "mrr");
    assert.deepEqual(metricEntry(sales), {type: "metric", metric: "sales", window: "year", usesWindow: true, average: 3, label: "Sales", unit: "USD", measure: "flow", color: "#2a78d6"});
    assert.deepEqual(metricSeries(metricEntry(sales), sales.color).key, sales.key);
});

test("refresh loads the loaded values of all levels again", async () => {
    let value = 1;
    const {values, requests} = cache(ids => ({dates: ["2026-01-31"], series: Object.fromEntries(ids.map(id => [id, {values: [value], unit: "USD"}]))}));
    await values.load("day", "day", ["sales", "mrr"]);
    await values.load("calendar-year", "year", ["sales"]);
    requests.length = 0;

    // the same values
    assert.equal((await values.refresh()).changed, false);
    assert.deepEqual(requests, [{window: "day", detail: "day", ids: ["sales", "mrr"]}, {window: "calendar-year", detail: "year", ids: ["sales"]}]);

    value = 2;
    const {changed, responses} = await values.refresh();
    assert.equal(changed, true);
    assert.deepEqual(responses.map(r => r.requested), [["sales", "mrr"], ["sales"]]);
    assert.deepEqual(values.data("day", "day").series.sales.values, [2]);
});

test("refresh detects new dates and keeps the values of failed requests", async () => {
    let dates = ["2026-01-31"];
    let fail = false;
    const values = new MetricValueCache(async (window, detail, ids) => {
        if (fail) {
            throw new Error("offline");
        }
        return response(dates, ids);
    });
    await values.load("day", "day", ["sales"]);
    dates = ["2026-01-31", "2026-02-01"];
    assert.equal((await values.refresh()).changed, true);
    assert.deepEqual(values.data("day", "day").dates, dates);

    fail = true;
    const originalError = console.error;
    console.error = () => {};
    try {
        assert.deepEqual(await values.refresh(), {changed: false, responses: []});
    } finally {
        console.error = originalError;
    }
    assert.deepEqual(values.data("day", "day").dates, dates);
    assert.equal(values.isLoading, false);
});

test("refresh skips levels without values", async () => {
    const {values, requests} = cache(() => response(["2026-01-31"], []));
    await values.load("day", "day", ["trials"]);
    requests.length = 0;
    assert.deepEqual(await values.refresh(), {changed: false, responses: []});
    assert.equal(requests.length, 0);
});
