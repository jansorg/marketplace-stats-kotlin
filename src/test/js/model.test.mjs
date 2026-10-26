/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

import {test} from "node:test";
import assert from "node:assert/strict";
import {isoDateToTimestamp, SECONDS_PER_DAY} from "../../main/resources/js/charts/format.js";
import {
    alignedRanges, niceStep, rangeTicks,
    averageShade, pinchedRange, rangeLabel, rangeOfTimestamps, rangeOfYear, rangeTimestamps, rangeYears,
    alignSeries, availableComparisons, cursorPosition, chartLevel, chartLevelKeys, finestDetail, lineDetail, periodEnd, shownWindow, storedChartLevel, tableWindow, windowApplies, withDetail, detailGranularity, detailPeriod, METRIC_PERIOD, metricLabel, yearEndTicks, DARK_INCOMPLETE_GREY, DARK_OTHER_SLICE_COLOR, DARK_SERIES_COLORS, displayColor, extremeIndices, INCOMPLETE_GREY, incompleteColor, overlayApplies, previousYear,
    seriesMeasure, trailingAverage, withoutIncomplete, barLayout, canCombineUnits, dateRange, exportFileName, metricPoints, groupCharts, groupUnits, layoutLabels, layoutLegend, MAX_PIE_SLICES,
    eventMenuItems, nextColor, OTHER_SLICE_COLOR, visibleMarkers, pieArcs, pieSlices, SERIES_COLORS, shortestPeriod, smallestInterval, zeroBasedRange, shiftDate, valueChange, valueNear,
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

test("the same metric of different tables is one series in the chart of its unit", () => {
    const sales = {key: "metric:sales", metric: "sales", period: METRIC_PERIOD, unit: "USD", points: new Map()};
    const trials = {key: "metric:trials", metric: "trials", period: METRIC_PERIOD, unit: "count", points: new Map()};
    const renewals = series("year", "%", [[50, 1]]);
    assert.deepEqual(groupCharts([sales, trials, renewals], false).map(({period, units}) => ({period, units})), [
        {period: METRIC_PERIOD, units: ["USD"]},
        {period: METRIC_PERIOD, units: ["count"]},
        {period: "year", units: ["%"]},
    ]);
});

test("the level of a chart of metrics", () => {
    const chart = {period: METRIC_PERIOD, units: ["USD", "count"]};
    assert.deepEqual(chartLevelKeys(chart), ["metric/USD+count", "metric/USD", "metric/count"]);
    // the level of the first unit until the combined chart has its own level
    const usd = {window: "year", detail: "day"};
    assert.deepEqual(chartLevel(chart, {"metric/USD": usd}), usd);
    assert.deepEqual(chartLevel(chart, {}), {window: "calendar-month", detail: "month"});
    assert.deepEqual(chartLevel({period: METRIC_PERIOD, units: ["count"]}, {"metric/count": usd}), usd);
});

test("lines with windows shorter than the steps of the dates have finer dates", () => {
    assert.equal(windowApplies("day", "day"), true);
    assert.equal(windowApplies("day", "year"), false);
    assert.equal(windowApplies("calendar-month", "month"), true);
    // the sales per day in a chart of months have values of each day
    assert.equal(lineDetail("day", "month"), "day");
    assert.equal(lineDetail("week", "year"), "week");
    assert.equal(lineDetail("calendar-month", "year"), "month");
    assert.equal(lineDetail("year", "month"), "month");
    assert.equal(finestDetail(["month", "day"]), "day");
    assert.equal(finestDetail(["year", "month"]), "month");
    // the window is kept
    assert.deepEqual(withDetail({window: "day", detail: "day"}, "year"), {window: "day", detail: "year"});
});

test("labels of metrics name the window of sums and rates", () => {
    assert.equal(metricLabel({label: "Trials", usesWindow: true}, "year"), "Trials, last 12 months");
    assert.equal(metricLabel({label: "Trials", usesWindow: true}, "calendar-month"), "Trials, calendar month");
    assert.equal(metricLabel({label: "MRR", usesWindow: false}, "year"), "MRR");
    assert.equal(detailPeriod("year"), "year");
    assert.equal(detailPeriod("day"), "month");
    assert.equal(detailGranularity("month"), "monthEnd");
    assert.equal(detailGranularity("week"), "week");
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

test("the menu of the events lists the custom events, a separator and the kinds of vertical lines", () => {
    const events = [{id: "a", timestamp: 10, label: "A"}, {id: "b", timestamp: 20, label: "B"}];
    assert.deepEqual(eventMenuItems(events, ["release", "ide"]), [
        {type: "event", event: events[0]},
        {type: "event", event: events[1]},
        {type: "separator"},
        {type: "marker", kind: "release"},
        {type: "marker", kind: "ide"},
    ]);
    // the separator only separates two groups
    assert.deepEqual(eventMenuItems([], ["price"]), [{type: "marker", kind: "price"}]);
    assert.deepEqual(eventMenuItems(events.slice(0, 1), []), [{type: "event", event: events[0]}]);
    assert.deepEqual(eventMenuItems([], []), []);
});

test("legend entries move to the next row if they don't fit", () => {
    assert.deepEqual(layoutLegend([], 100, 10), []);
    assert.deepEqual(layoutLegend([40, 40, 40], 100, 10), [{x: 0, row: 0}, {x: 50, row: 0}, {x: 0, row: 1}]);
    // an entry wider than the row gets its own row
    assert.deepEqual(layoutLegend([150, 20], 100, 10), [{x: 0, row: 0}, {x: 0, row: 1}]);
    assert.deepEqual(layoutLegend([20, 150], 100, 10), [{x: 0, row: 0}, {x: 0, row: 1}]);
});

test("file names of exported charts contain only letters, digits and dashes", () => {
    assert.equal(exportFileName(["My Plugin: Report", "Sales (USD)"], "png"), "my-plugin-report-sales-usd.png");
    assert.equal(exportFileName(["Übersicht – Café", "% of Sales"], "svg"), "ubersicht-cafe-of-sales.svg");
    assert.equal(exportFileName(["", "***"], "png"), "chart.png");
});

test("values per day are read from the response of the server", () => {
    const data = {
        dates: ["2026-01-01", "2026-01-02", "2026-01-03"],
        series: {mrr: {unit: "USD", values: [1, 2.5, 3], incompleteFrom: 2}},
    };
    const {points, incomplete} = metricPoints(data, "mrr");
    assert.deepEqual([...points.entries()], [[1767225600, 1], [1767312000, 2.5], [1767398400, 3]]);
    assert.deepEqual([...incomplete], [1767398400]);
    assert.equal(metricPoints(data, "arr"), null);
    assert.equal(metricPoints(null, "mrr"), null);
});

test("dates are shifted by years and months, at most to the last day of the month", () => {
    const date = isoDateToTimestamp;
    assert.equal(shiftDate(date("2025-03-15"), 1, 0), date("2026-03-15"));
    assert.equal(shiftDate(date("2025-03-15"), -1, 0), date("2024-03-15"));
    assert.equal(shiftDate(date("2024-02-29"), 1, 0), date("2025-02-28"));
    assert.equal(shiftDate(date("2025-03-31"), 0, -1), date("2025-02-28"));
    assert.equal(shiftDate(date("2025-01-31"), 0, -1), date("2024-12-31"));
    assert.equal(shiftDate(date("2025-12-15"), 0, 1), date("2026-01-15"));
    // the last day of a month moves to the last day of the other month
    assert.equal(shiftDate(date("2025-09-30"), 0, -1), date("2025-08-31"));
    assert.equal(shiftDate(date("2025-02-28"), 0, 1), date("2025-03-31"));
    assert.equal(shiftDate(date("2023-02-28"), 1, 0), date("2024-02-29"));
    assert.equal(shiftDate(date("2025-09-29"), 0, -1), date("2025-08-29"));
});

test("comparisons need values at least as frequent as their distance", () => {
    const labels = interval => availableComparisons(interval).map(c => c.label);
    assert.deepEqual(labels(SECONDS_PER_DAY), ["−1 year", "−1 month", "+1 month", "+1 year"]);
    assert.deepEqual(labels(31 * SECONDS_PER_DAY), ["−1 year", "−1 month", "+1 month", "+1 year"]);
    assert.deepEqual(labels(365 * SECONDS_PER_DAY), ["−1 year", "+1 year"]);
});

test("the value closest to a date is found within the tolerance", () => {
    const points = [10, 20, 40].map(timestamp => ({timestamp, value: timestamp / 10}));
    assert.deepEqual(valueNear(points, 21, 5), {timestamp: 20, value: 2});
    assert.deepEqual(valueNear(points, 33, 10), {timestamp: 40, value: 4});
    assert.deepEqual(valueNear(points, 5, 5), {timestamp: 10, value: 1});
    assert.equal(valueNear(points, 30, 5), null);
    assert.equal(valueNear([], 30, 5), null);
});

test("incomplete values are removed from a series", () => {
    const original = {label: "MRR", points: new Map([[1, 10], [2, 20], [3, 30]]), incomplete: new Set([3])};
    const result = withoutIncomplete(original);
    assert.deepEqual([...result.points.keys()], [1, 2]);
    assert.equal(result.incomplete.size, 0);
    assert.equal(result.label, "MRR");
    // the original series is unchanged
    assert.equal(original.points.size, 3);
});

test("the minimum and the maximum ignore missing and incomplete values", () => {
    assert.deepEqual(extremeIndices([1, 2, 3, 4], [5, null, 9, 1], new Set([4])), {min: 0, max: 2});
    assert.deepEqual(extremeIndices([1, 2, 3], [5, 9, 9], new Set()), {min: 0, max: 1});
    // only the values within the shown dates
    assert.deepEqual(extremeIndices([1, 2, 3, 4], [1, 5, 3, 9], new Set(), [2, 3]), {min: 2, max: 1});
    assert.equal(extremeIndices([1, 2], [5, 5], new Set()), null);
    assert.equal(extremeIndices([1], [5], new Set()), null);
    assert.equal(extremeIndices([1, 2], [5, 9], new Set([1, 2])), null);
});

test("incomplete values have a lighter, less saturated color", () => {
    const toHsl = hex => {
        const [r, g, b] = [1, 3, 5].map(i => Number.parseInt(hex.substring(i, i + 2), 16) / 255);
        const max = Math.max(r, g, b), min = Math.min(r, g, b), l = (max + min) / 2;
        const s = max === min ? 0 : l > 0.5 ? (max - min) / (2 - max - min) : (max - min) / (max + min);
        return {s, l};
    };
    for (const color of SERIES_COLORS.filter(color => incompleteColor(color) !== INCOMPLETE_GREY)) {
        const original = toHsl(color);
        const incomplete = toHsl(incompleteColor(color));
        assert.match(incompleteColor(color), /^#[0-9a-f]{6}$/);
        assert.ok(incomplete.s < original.s, color);
        assert.ok(incomplete.l > original.l, color);
    }
    // colors with a lighter color, which is too similar, use grey
    assert.equal(incompleteColor("#e87ba4"), INCOMPLETE_GREY);
    assert.equal(incompleteColor("#808080"), INCOMPLETE_GREY);
    assert.equal(incompleteColor("#2a78d6"), "#76a2d6");
    assert.equal(incompleteColor("red"), "red");
});

test("incomplete values of the dark mode are faded towards the dark surface", () => {
    for (const color of DARK_SERIES_COLORS) {
        const incomplete = incompleteColor(color, true);
        assert.match(incomplete, /^#[0-9a-f]{6}$/);
        if (incomplete !== DARK_INCOMPLETE_GREY) {
            const lightness = hex => Math.max(...[1, 3, 5].map(i => Number.parseInt(hex.substring(i, i + 2), 16)));
            assert.ok(lightness(incomplete) < lightness(color), color);
        }
    }
    // dark colors, whose darker color is too similar, use grey
    assert.equal(incompleteColor("#303030", true), DARK_INCOMPLETE_GREY);
});

test("the colors of the dark mode replace the colors of the light mode", () => {
    assert.equal(DARK_SERIES_COLORS.length, SERIES_COLORS.length);
    SERIES_COLORS.forEach((color, index) => {
        assert.equal(displayColor(color, false), color);
        assert.equal(displayColor(color, true), DARK_SERIES_COLORS[index]);
    });
    assert.equal(displayColor(OTHER_SLICE_COLOR, true), DARK_OTHER_SLICE_COLOR);
    assert.equal(displayColor("#123456", true), "#123456");
});

test("changes of values are percentages, changes of percentages are percentage points", () => {
    assert.deepEqual(valueChange(50, 75, "USD"), {value: 50, unit: "%"});
    assert.deepEqual(valueChange(-50, -25, "count"), {value: 50, unit: "%"});
    assert.deepEqual(valueChange(10, 7.5, "%"), {value: -2.5, unit: "pp"});
    assert.equal(valueChange(0, 10, "USD"), null);
    assert.equal(valueChange(null, 10, "USD"), null);
});

test("values per day start with the last zero before the first value", () => {
    const data = {
        dates: ["2026-01-01", "2026-01-02", "2026-01-03", "2026-01-04"],
        series: {mrr: {values: [0, 0, 5, 0], incompleteFrom: 4}, arr: {values: [0, 0, 0, 0], incompleteFrom: 4}},
    };
    assert.deepEqual([...metricPoints(data, "mrr").points.values()], [0, 5, 0]);
    assert.deepEqual([...metricPoints(data, "arr").points.values()], [0, 0, 0, 0]);
});

test("averages apply to monthly values, the previous year to all except yearly values", () => {
    // the values of tables
    const flow = {period: "month", measure: "flow", unit: "USD"};
    assert.equal(overlayApplies("average3", flow, null), true);
    assert.equal(overlayApplies("average1", flow, null), false);
    assert.equal(overlayApplies("average3", {period: "year", measure: "flow", unit: "USD"}, null), false);
    assert.equal(overlayApplies("previousYear", {period: "year", measure: "flow", unit: "USD"}, null), false);
    // metrics, the averages are loaded from the server
    const metric = {period: "month", metric: "sales", unit: "USD", usesWindow: true};
    assert.equal(overlayApplies("average12", metric, "day", "month"), true);
    assert.equal(overlayApplies("average3", metric, "year", "calendar-month"), true);
    assert.equal(overlayApplies("previousYear", metric, "day"), true);
    assert.equal(overlayApplies("previousYear", {...metric, period: "year"}, "year"), false);
    assert.equal(seriesMeasure({unit: "%"}), "ratio");
    assert.equal(seriesMeasure({unit: "count"}), null);
});

function monthly(values, incomplete = []) {
    const points = new Map(values.map(([month, value]) => [isoDateToTimestamp(month), value]));
    return {points, incomplete: new Set(incomplete.map(isoDateToTimestamp))};
}

test("moving averages of months", () => {
    const series = monthly([["2025-01-31", 10], ["2025-02-28", 20], ["2025-03-31", 30], ["2025-04-30", 70], ["2025-05-31", 99]], ["2025-05-31"]);
    const averages = trailingAverage(series, 3);
    assert.deepEqual([...averages.entries()], [[isoDateToTimestamp("2025-03-31"), 20], [isoDateToTimestamp("2025-04-30"), 40]]);
    // a missing month isn't counted as zero
    const gap = monthly([["2025-01-31", 10], ["2025-02-28", 20], ["2025-04-30", 30], ["2025-05-31", 40], ["2025-06-30", 50]]);
    assert.deepEqual([...trailingAverage(gap, 3).values()], [40]);
    assert.equal(trailingAverage(monthly([["2025-01-31", 10]]), 3).size, 0);
});

test("the previous year is shifted by a year and ends with the series", () => {
    const series = monthly([["2024-01-31", 1], ["2024-02-29", 2], ["2025-01-31", 3], ["2025-02-28", 4]], ["2025-02-28"]);
    const shifted = previousYear(series);
    assert.deepEqual([...shifted.points.entries()], [[isoDateToTimestamp("2025-01-31"), 1], [isoDateToTimestamp("2025-02-28"), 2]]);
    assert.equal(shifted.incomplete.size, 0);
});


test("the ticks of years are at the values of the years, at the end of each year and at today", () => {
    const day = isoDateToTimestamp;
    // a month before the value of 2022 until the end of the current year, the current year has its tick at today
    assert.deepEqual(yearEndTicks(day("2022-12-01"), day("2026-12-31"), day("2026-10-26")),
        [day("2022-12-31"), day("2023-12-31"), day("2024-12-31"), day("2025-12-31"), day("2026-10-26")]);
    // the current year has no value yet
    assert.deepEqual(yearEndTicks(day("2022-12-01"), day("2024-01-05"), day("2023-12-31")), [day("2022-12-31"), day("2023-12-31")]);
    assert.deepEqual(yearEndTicks(day("2026-01-01"), day("2026-10-26"), undefined), []);
});

test("custom events are shown one by one", () => {
    const markers = [
        {kind: "custom", id: "b", timestamp: 20, label: "Blog post"},
        {kind: "release", timestamp: 10, label: "v2"},
        {kind: "custom", id: "a", timestamp: 5, label: "Trial period"},
    ];
    assert.deepEqual(visibleMarkers(markers, ["release"], ["b"]).map(m => m.label), ["v2", "Blog post"]);
    assert.deepEqual(visibleMarkers(markers, [], ["a", "b"]).map(m => m.label), ["Trial period", "Blog post"]);
    assert.deepEqual(visibleMarkers(markers, ["release"]).map(m => m.label), ["v2"]);
});

test("averages of metrics apply to values at a date and to months longer than the window", () => {
    const sums = {period: "month", unit: "USD", metric: "sales", usesWindow: true};
    assert.equal(overlayApplies("average1", sums, "day", "day"), true);
    assert.equal(overlayApplies("average1", sums, "month", "calendar-month"), false);
    assert.equal(overlayApplies("average3", sums, "month", "month"), true);
    assert.equal(overlayApplies("average3", sums, "month", "quarter"), false);
    assert.equal(overlayApplies("average12", sums, "year", "year"), false);
    // values at a date don't depend on the window
    assert.equal(overlayApplies("average1", {...sums, usesWindow: false}, "year", "year"), true);
});

test("date ranges of the line charts", () => {
    const day = isoDateToTimestamp;
    const series = [{points: new Map([[day("2023-12-31"), 1], [day("2025-01-31"), 2]])}, {points: new Map([[day("2024-06-30"), 3]])}];
    assert.deepEqual(rangeYears(series), [2025, 2024, 2023]);
    assert.deepEqual(rangeYears([]), []);

    assert.deepEqual(rangeOfYear(2024), {from: "2024-01-01", to: "2024-12-31"});
    assert.deepEqual(rangeTimestamps(rangeOfYear(2024)), [day("2024-01-01"), day("2025-01-01") - 1]);
    assert.equal(rangeTimestamps(null), null);

    // a selection within days and from right to left
    assert.deepEqual(rangeOfTimestamps(day("2024-03-05") + 3600, day("2024-02-01") + 100), {from: "2024-02-01", to: "2024-03-05"});

    assert.equal(rangeLabel(null), "All");
    assert.equal(rangeLabel(rangeOfYear(2024)), "2024");
    assert.equal(rangeLabel({from: "2024-02-01", to: "2024-03-05"}), "2024-02-01 – 2024-03-05");
});

test("pinching keeps the dates under the fingers", () => {
    const limits = [0, 1000];
    // the fingers move apart around the center, the range is halved around the center
    assert.deepEqual(pinchedRange([0, 1000], [400, 600], [300, 700], 1000, limits), [250, 750]);
    // both fingers move right, the range moves left by the same dates
    assert.deepEqual(pinchedRange([200, 600], [400, 600], [500, 700], 1000, limits), [160, 560]);
    // zooming out stops at the dates of the values
    assert.deepEqual(pinchedRange([250, 750], [300, 700], [450, 550], 1000, limits), [0, 1000]);
    assert.deepEqual(pinchedRange([800, 1000], [400, 600], [450, 550], 1000, limits), [600, 1000]);
    // fingers at the same position don't change the range
    assert.deepEqual(pinchedRange([0, 1000], [500, 500], [300, 700], 1000, limits), [0, 1000]);
});

test("several averages of a series have different shades of its color", () => {
    assert.equal(averageShade("#2a78d6", 0, 1), "#2a78d6");
    const shades = [0, 1, 2].map(index => averageShade("#2a78d6", index, 3));
    assert.equal(new Set(shades).size, 3);
    // the middle shade is the color of the series, the first is lighter and the last darker
    assert.equal(shades[1], "#2a78d6");
    const lightness = color => [1, 3, 5].reduce((sum, i) => sum + Number.parseInt(color.substring(i, i + 2), 16), 0);
    assert.ok(lightness(shades[0]) > lightness(shades[1]) && lightness(shades[1]) > lightness(shades[2]));
    assert.equal(averageShade("not a color", 0, 2), "not a color");
});

test("the cursor stays inside the chart at values on its edges", () => {
    // values at -3, 100 and 200 pixels in a chart of 150 pixels
    const positions = [-3, 100, 200];
    const position = index => cursorPosition(index, i => positions[i], positions.length, 150, 5);
    // a point on the y-axis keeps the cursor at the edge
    assert.equal(position(0), 0);
    assert.equal(position(1), 100);
    // a value right of the chart isn't drawn, the cursor is at the previous value
    assert.equal(position(2), 100);
    // a value far left of the chart isn't drawn
    assert.equal(cursorPosition(0, i => [-40, 30][i], 2, 150, 5), 30);
});

test("the selected window of a chart is kept at all levels", () => {
    const chart = {period: METRIC_PERIOD, units: ["USD"]};
    const levels = {"metric/USD": {window: "calendar-month", detail: "year"}};
    assert.deepEqual(chartLevel(chart, levels), {window: "calendar-month", detail: "year"});
    assert.deepEqual(storedChartLevel(chart, levels), {window: "calendar-month", detail: "year"});
    // the window, which follows the level
    assert.deepEqual(chartLevel(chart, {"metric/USD": {window: "follow", detail: "year"}}), {window: "calendar-year", detail: "year"});
});

test("the date axis ends with the period of the last value", () => {
    const day = isoDateToTimestamp;
    assert.equal(periodEnd(day("2026-10-26"), "yearEnd"), day("2026-12-31"));
    assert.equal(periodEnd(day("2026-10-26"), "monthEnd"), day("2026-10-31"));
    assert.equal(periodEnd(day("2026-02-10"), "month"), day("2026-02-28"));
    // Monday, the week ends at Sunday
    assert.equal(periodEnd(day("2026-10-26"), "week"), day("2026-11-01"));
    assert.equal(periodEnd(day("2026-11-01"), "week"), day("2026-11-01"));
    assert.equal(periodEnd(day("2026-10-26"), "day"), day("2026-10-26"));
});

test("the window, which follows the level, is the calendar window of the level", () => {
    assert.equal(shownWindow("follow", "year"), "calendar-year");
    assert.equal(shownWindow("follow", "month"), "calendar-month");
    assert.equal(shownWindow("follow", "week"), "week");
    assert.equal(shownWindow("follow", "day"), "day");
    // other windows are kept, if they apply
    assert.equal(shownWindow("year", "month"), "year");
    // the calendar window of the level of a table follows the level of the chart
    assert.equal(tableWindow("calendar-month", "month"), "follow");
    assert.equal(tableWindow("calendar-year", "year"), "follow");
    assert.equal(tableWindow("day", "day"), "follow");
    assert.equal(tableWindow("year", "month"), "year");
});

test("the step of an axis is 1, 2, 2.5 or 5 times a power of ten", () => {
    assert.equal(niceStep(450, 5), 100);
    assert.equal(niceStep(120, 5), 25);
    assert.equal(niceStep(0.9, 5), 0.2);
    assert.equal(niceStep(0, 5), 1);
});

test("two y-axes have their ticks at the same heights", () => {
    const [left, right] = alignedRanges([[0, 450], [0, 31]], 5);
    assert.deepEqual(left, {min: 0, max: 500, step: 100});
    assert.deepEqual(right, {min: 0, max: 50, step: 10});
    assert.equal(rangeTicks(left).length, rangeTicks(right).length);
    // negative values of one axis add steps below zero to both axes
    const [amounts, rates] = alignedRanges([[-120, 400], [0, 80]], 5);
    assert.deepEqual(rangeTicks(amounts), [-200, -100, 0, 100, 200, 300, 400]);
    assert.deepEqual(rangeTicks(rates), [-40, -20, 0, 20, 40, 60, 80]);
    assert.deepEqual(rangeTicks({min: 0, max: 0.6, step: 0.2}), [0, 0.2, 0.4, 0.6]);
});
