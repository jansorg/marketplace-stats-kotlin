/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

import {test} from "node:test";
import assert from "node:assert/strict";
import {
    alignSeries, barLayout, canCombineUnits, dateRange, groupCharts, groupUnits, layoutLabels, MAX_PIE_SLICES,
    nextColor, OTHER_SLICE_COLOR, visibleMarkers, pieArcs, pieSlices, SERIES_COLORS, shortestPeriod, smallestInterval, zeroBasedRange,
} from "../../main/resources/js/charts/model.js";

function series(period, unit, points) {
    return {period, unit, points: new Map(points)};
}

test("colors are assigned in the order of the palette", () => {
    assert.equal(nextColor([]), SERIES_COLORS[0]);
    assert.equal(nextColor([SERIES_COLORS[0]]), SERIES_COLORS[1]);
    // a stored color is kept if it's not used yet
    assert.equal(nextColor([SERIES_COLORS[0]], SERIES_COLORS[3]), SERIES_COLORS[3]);
    assert.equal(nextColor([SERIES_COLORS[3]], SERIES_COLORS[3]), SERIES_COLORS[0]);
    assert.equal(nextColor([], "#123456"), SERIES_COLORS[0]);
    assert.equal(nextColor(SERIES_COLORS), undefined);
});

test("units have separate charts, amounts are on the left axis with a second y-axis", () => {
    assert.deepEqual(groupUnits(["%", "USD", "count"], false), [["%"], ["USD"], ["count"]]);
    assert.deepEqual(groupUnits(["%", "USD", "count"], true), [["USD", "%"], ["count"]]);
    assert.deepEqual(groupUnits(["%", "count"], true), [["%", "count"]]);
    assert.deepEqual(groupUnits(["USD"], true), [["USD"]]);
});

test("only series of the same period share a chart and its date range", () => {
    const monthly = series("month", "USD", [[100, 1], [200, 2]]);
    const yearly = series("year", "USD", [[50, 1], [300, 2]]);
    const daily = series("day", "count", [[290, 1], [295, 2]]);
    const charts = groupCharts([yearly, daily, monthly], false);
    assert.deepEqual(charts, [
        {period: "year", units: ["USD"], dateRange: [50, 300]},
        {period: "day", units: ["count"], dateRange: [290, 295]},
        {period: "month", units: ["USD"], dateRange: [100, 200]},
    ]);
    // a second y-axis only combines units of the same period
    assert.equal(canCombineUnits([yearly, daily]), false);
    assert.equal(canCombineUnits([monthly, series("month", "%", [[100, 1]])]), true);
});

test("date ranges and aligned series", () => {
    assert.equal(dateRange([]), null);
    assert.deepEqual(dateRange([series("day", "USD", [[100, 1]])]), [100 - 86400, 100 + 86400]);

    const aligned = alignSeries([series("month", "USD", [[200, 2], [100, 1]]), series("month", "%", [[300, 3]])]);
    assert.deepEqual(aligned.timestamps, [100, 200, 300]);
    assert.deepEqual(aligned.values, [[1, 2, null], [null, null, 3]]);
    assert.equal(shortestPeriod([{period: "year"}, {period: "month"}]), "month");
    assert.equal(shortestPeriod([]), "day");
});

test("y-axes start at 0", () => {
    assert.deepEqual(zeroBasedRange(5, 10), [0, 10]);
    assert.deepEqual(zeroBasedRange(-5, 10), [-5, 10]);
    assert.deepEqual(zeroBasedRange(-5, -1), [-5, 0]);
});

test("pie charts combine the smaller rows into an other slice", () => {
    const rows = Array.from({length: 10}, (_, i) => ({label: "Row " + i, value: 10 - i, details: []}));
    const slices = pieSlices(rows);
    assert.equal(slices.length, MAX_PIE_SLICES + 1);
    assert.equal(slices[0].label, "Row 0");
    assert.equal(slices[0].color, SERIES_COLORS[0]);
    const other = slices[slices.length - 1];
    assert.equal(other.label, "Other");
    assert.equal(other.color, OTHER_SLICE_COLOR);
    // 100% - (10 + 9 + ... + 4)
    assert.equal(other.value, 100 - 49);
    assert.deepEqual(other.details, [["Includes", "3 more rows of the table and rows not shown in the table"]]);
});

test("pie charts without an other slice if all rows are shown", () => {
    const slices = pieSlices([{label: "A", value: 60, details: []}, {label: "B", value: 40, details: []}, {label: "C", value: 0, details: []}]);
    assert.deepEqual(slices.map(s => s.label), ["A", "B"]);
});

test("pie arcs are scaled to the total and don't overlap", () => {
    const arcs = pieArcs([60, 40.02, 0.03]);
    const end = arcs[arcs.length - 1].endAngle;
    assert.ok(Math.abs(end - (-Math.PI / 2 + 2 * Math.PI)) < 1e-9);
    assert.equal(pieArcs([100])[0].isFullCircle, true);
    assert.equal(pieArcs([50, 50])[0].isFullCircle, false);
});

test("overlapping labels are skipped and labels stay inside", () => {
    const centers = layoutLabels([{x: 2, width: 20}, {x: 15, width: 20}, {x: 100, width: 20}, {x: 199, width: 20}], 200, 6);
    assert.deepEqual(centers, [10, null, 100, 190]);
});

test("bars of a group are side by side, thin and centered", () => {
    const layout = barLayout(2, 100, {maxBarWidth: 24, gap: 2});
    assert.equal(layout.width, 24);
    assert.deepEqual(layout.offsets, [-25, 1]);
    const narrow = barLayout(3, 20, {maxBarWidth: 24, gap: 2});
    assert.ok(narrow.width * 3 + 4 <= 20 * 0.7 + 1e-9);
    assert.deepEqual(barLayout(0, 100, {maxBarWidth: 24, gap: 2}), {width: 0, offsets: []});
    assert.equal(smallestInterval([10, 40, 50], 1), 10);
    assert.equal(smallestInterval([10], 7), 7);
});

test("only the shown kinds of vertical lines are drawn, sorted by date", () => {
    const markers = [{kind: "ide", timestamp: 30}, {kind: "release", timestamp: 10}, {kind: "price", timestamp: 20}];
    assert.deepEqual(visibleMarkers(markers, ["ide", "release"]).map(m => m.timestamp), [10, 30]);
    // hidden by default
    assert.deepEqual(visibleMarkers(markers, []), []);
});
