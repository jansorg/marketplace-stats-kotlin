/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

/**
 * Pure functions for the data of the charts. They don't access the DOM and are tested with Node.js.
 */

import {isAmountUnit, isoDateToTimestamp, PERIODS, SECONDS_PER_DAY} from "./format.js";

/** Categorical palette, assigned in fixed order. */
export const SERIES_COLORS = ["#2a78d6", "#eb6834", "#1baf7a", "#eda100", "#e87ba4", "#008300", "#6250d6", "#e34948"];
export const OTHER_SLICE_COLOR = "#a8a7a2";
/**
 * The colors of {@link SERIES_COLORS} for the dark mode: the same hues, stepped for the dark surface, so that each
 * has a contrast of at least 3:1 and the neighbors stay distinguishable with color vision deficiencies.
 * The stored setup of the charts always uses the colors of the light mode.
 */
export const DARK_SERIES_COLORS = ["#3987e5", "#d95926", "#199e70", "#c98500", "#d55181", "#008300", "#9085e9", "#e66767"];
export const DARK_OTHER_SLICE_COLOR = "#8a8993";

/**
 * @param {string} color A color of {@link SERIES_COLORS} or {@link OTHER_SLICE_COLOR}.
 * @param {boolean} dark If the color is shown in the dark mode.
 * @return {string} The color, which is shown. Other colors are returned unchanged.
 */
export function displayColor(color, dark) {
    if (!dark) {
        return color;
    }
    if (color === OTHER_SLICE_COLOR) {
        return DARK_OTHER_SLICE_COLOR;
    }
    const index = SERIES_COLORS.indexOf(color);
    return index === -1 ? color : DARK_SERIES_COLORS[index];
}
export const MAX_SERIES = SERIES_COLORS.length;
export const MAX_PIES = 4;
/** Pie charts show the largest rows, the other rows are combined into one slice. */
export const MAX_PIE_SLICES = SERIES_COLORS.length - 1;

/**
 * @return {string} Key of a chart, which identifies a column of a table.
 */
export function chartKey(type, table, column) {
    return type + "/" + table + "/" + column;
}

/**
 * @return {string|undefined} The first color of the palette, which isn't used yet.
 */
export function nextColor(usedColors, preferredColor = undefined) {
    const used = new Set(usedColors);
    if (preferredColor !== undefined && SERIES_COLORS.includes(preferredColor) && !used.has(preferredColor)) {
        return preferredColor;
    }
    return SERIES_COLORS.find(color => !used.has(color));
}

/**
 * Groups the units of the line charts. Each unit has its own chart.
 * With a second y-axis, amounts are on the left y-axis and the first other unit on the right y-axis.
 *
 * @param {string[]} units Units in the order they were added.
 * @param {boolean} combine If a second y-axis is used.
 * @return {string[][]} The units of each chart.
 */
export function groupUnits(units, combine) {
    if (!combine || units.length < 2) {
        return units.map(unit => [unit]);
    }
    const left = units.find(isAmountUnit) ?? units[0];
    const right = units.find(unit => unit !== left);
    return [[left, right], ...units.filter(unit => unit !== left && unit !== right).map(unit => [unit])];
}

/**
 * Groups the series into line charts. Only series of the same period, e.g. monthly values, share a date range,
 * because daily values of a few days are unreadable in a chart of several years.
 * Within a period, each unit has its own chart, or two units share a chart with a second y-axis.
 *
 * @param {{period: string, unit: string}[]} seriesList Series in the order they were added.
 * @param {boolean} combineUnits If a second y-axis is used.
 * @return {{period: string, units: string[], dateRange: [number, number]}[]} The line charts, ordered by the
 *  first series of each period.
 */
export function groupCharts(seriesList, combineUnits) {
    const periods = [...new Set(seriesList.map(series => series.period))];
    return periods.flatMap(period => {
        const periodSeries = seriesList.filter(series => series.period === period);
        const units = [...new Set(periodSeries.map(series => series.unit))];
        const range = dateRange(periodSeries);
        return groupUnits(units, combineUnits).map(groupedUnits => ({period, units: groupedUnits, dateRange: range}));
    });
}

/**
 * @return {boolean} If a second y-axis would combine any charts.
 */
export function canCombineUnits(seriesList) {
    const periods = new Set(seriesList.map(series => series.period));
    return [...periods].some(period => new Set(seriesList.filter(series => series.period === period).map(series => series.unit)).size >= 2);
}

/**
 * @return {[number, number]|null} The date range of all series, used by all line charts of a period.
 */
export function dateRange(seriesList) {
    let min = Infinity;
    let max = -Infinity;
    for (const series of seriesList) {
        for (const timestamp of series.points.keys()) {
            min = Math.min(min, timestamp);
            max = Math.max(max, timestamp);
        }
    }
    if (min === Infinity) {
        return null;
    }
    // a single date needs a range to be shown
    return min === max ? [min - SECONDS_PER_DAY, max + SECONDS_PER_DAY] : [min, max];
}

/**
 * @typedef {{from: string, to: string}} DateRange The selected dates of the line charts, ISO dates, both inclusive.
 */

/**
 * @return {number[]} The years with values of the series, the latest year first, e.g. to select the range of a year.
 */
export function rangeYears(seriesList) {
    const years = new Set(seriesList.flatMap(series => [...series.points.keys()].map(timestamp => new Date(timestamp * 1000).getUTCFullYear())));
    return [...years].sort((a, b) => b - a);
}

/** @return {DateRange} The range of all days of the year. */
export function rangeOfYear(year) {
    return {from: `${year}-01-01`, to: `${year}-12-31`};
}

/**
 * @param {number} from The timestamp of the first date, e.g. of the start of a selection in a chart.
 * @param {number} to The timestamp of the last date.
 * @return {DateRange} The range of the days of the dates.
 */
export function rangeOfTimestamps(from, to) {
    const day = timestamp => new Date(Math.floor(timestamp / SECONDS_PER_DAY) * SECONDS_PER_DAY * 1000).toISOString().substring(0, 10);
    return from <= to ? {from: day(from), to: day(to)} : {from: day(to), to: day(from)};
}

/**
 * @param {DateRange|null} range
 * @return {[number, number]|null} The timestamps of the start of the first day and of the end of the last day.
 */
export function rangeTimestamps(range) {
    return range === null ? null : [isoDateToTimestamp(range.from), isoDateToTimestamp(range.to) + SECONDS_PER_DAY - 1];
}

/**
 * The shown dates while two fingers pinch a chart: the dates under the fingers stay under them.
 *
 * @param {[number, number]} shown The shown dates when the fingers touched the chart.
 * @param {[number, number]} start The positions of the fingers on the x-axis at the start, in pixels from the left.
 * @param {[number, number]} current The positions of the fingers now.
 * @param {number} width The width of the plot in pixels.
 * @param {[number, number]} limits The dates of the values, the chart doesn't show dates before and after them.
 * @return {[number, number]} The dates, which are shown now.
 */
export function pinchedRange(shown, start, current, width, limits) {
    const startDistance = Math.abs(start[1] - start[0]);
    const currentDistance = Math.abs(current[1] - current[0]);
    if (width <= 0 || startDistance < 1 || currentDistance < 1) {
        return shown;
    }
    const scale = (shown[1] - shown[0]) / width;
    const center = shown[0] + (start[0] + start[1]) / 2 * scale;
    const span = Math.min((shown[1] - shown[0]) * startDistance / currentDistance, limits[1] - limits[0]);
    const from = center - (current[0] + current[1]) / 2 / width * span;
    // the range is moved back within the limits
    const clamped = Math.min(Math.max(from, limits[0]), limits[1] - span);
    return [clamped, clamped + span];
}

/**
 * @param {DateRange|null} range
 * @return {string} The label of the range in the menu, e.g. "2025" for a year, "All" without a range.
 */
export function rangeLabel(range) {
    if (range === null) {
        return "All";
    }
    const year = range.from.substring(0, 4);
    if (range.from === `${year}-01-01` && range.to === `${year}-12-31`) {
        return year;
    }
    return `${range.from} – ${range.to}`;
}

/**
 * Aligns the values of several series to the union of their dates, as needed by uPlot.
 *
 * @return {{timestamps: number[], values: (number|null)[][]}}
 */
export function alignSeries(seriesList) {
    const timestamps = [...new Set(seriesList.flatMap(series => [...series.points.keys()]))].sort((a, b) => a - b);
    const values = seriesList.map(series => timestamps.map(timestamp => series.points.get(timestamp) ?? null));
    return {timestamps, values};
}

/**
 * @return {string} The shortest period of the series, dates of a chart are shown with this precision.
 */
export function shortestPeriod(seriesList) {
    return PERIODS.find(period => seriesList.some(series => series.period === period)) ?? "day";
}

/**
 * Y-axes start at 0, because amounts, counts and percentages are magnitudes. Negative values extend the range.
 *
 * @return {[number, number]} The range of the values, before uPlot adds a padding and rounds it.
 */
export function zeroBasedRange(min, max) {
    return [Math.min(0, min), Math.max(0, max)];
}

/**
 * @param {number} span The difference of the largest and the smallest value of an axis.
 * @param {number} count The preferred number of steps of the axis.
 * @return {number} The smallest step of 1, 2, 2.5 or 5 times a power of ten, with at most `count` steps over the span.
 */
export function niceStep(span, count) {
    const raw = span / Math.max(1, count);
    if (!(raw > 0)) {
        return 1;
    }
    const magnitude = 10 ** Math.floor(Math.log10(raw));
    return [1, 2, 2.5, 5, 10].map(factor => factor * magnitude).find(step => step >= raw * (1 - 1e-9));
}

/** @return {number} The smallest step of 1, 2, 2.5 or 5 times a power of ten, which isn't smaller than the value. */
function niceCeil(value) {
    const magnitude = 10 ** Math.floor(Math.log10(value));
    return [1, 2, 2.5, 5, 10].map(factor => factor * magnitude).find(step => step >= value * (1 - 1e-9));
}

/**
 * Ranges of y-axes, whose ticks are at the same heights, so that the grid lines of one axis are also at the values of
 * the other axes. Each axis has its own step, all axes have the same number of steps below and above zero. Of the
 * possible numbers of steps, the one with the least unused space of the axes is used.
 *
 * @param {[number, number][]} extents The smallest and the largest value of each axis, the ranges include zero.
 * @param {number} count The preferred number of steps.
 * @return {{min: number, max: number, step: number}[]} The range and the step of the ticks of each axis.
 */
export function alignedRanges(extents, count) {
    const ranges = extents.map(([min, max]) => zeroBasedRange(min, max));
    let best = null;
    for (let total = Math.max(2, count - 2); total <= count + 3; total++) {
        for (let below = 0; below < total; below++) {
            const above = total - below;
            if (below === 0 && ranges.some(([min]) => min < 0)) {
                continue;
            }
            const steps = ranges.map(([min, max]) => {
                const needed = Math.max(max / above, below > 0 ? -min / below : 0);
                return needed > 0 ? niceCeil(needed) : niceStep(1, total);
            });
            // the unused part of each axis, more steps than preferred are slightly worse
            const unused = ranges.reduce((sum, [min, max], index) => sum + 1 - (max - min) / (total * steps[index]), 0);
            const score = unused + 0.05 * Math.abs(total - count);
            if (best === null || score < best.score - 1e-9) {
                best = {score, below, above, steps};
            }
        }
    }
    return best.steps.map(step => ({min: best.below === 0 ? 0 : -best.below * step, max: best.above * step, step}));
}

/**
 * @return {number[]} The ticks of a range of {@link alignedRanges}, without rounding errors of the floating point numbers.
 */
export function rangeTicks({min, max, step}) {
    const count = Math.round((max - min) / step);
    return Array.from({length: count + 1}, (_, index) => Number((min + index * step).toPrecision(12)));
}

/**
 * Creates the slices of a pie chart.
 *
 * @param {{label: string, value: number, details: string[][]}[]} rows Rows of the table with their share in percent.
 * @return {{label: string, value: number, color: string, details: string[][]}[]} The largest rows and an "Other" slice
 *  for the remaining rows and for the rows, which aren't shown in the table.
 */
export function pieSlices(rows) {
    const sorted = rows.filter(row => row.value > 0).sort((a, b) => b.value - a.value);
    const slices = sorted.slice(0, MAX_PIE_SLICES).map((row, index) => ({...row, color: SERIES_COLORS[index]}));
    const otherRows = sorted.slice(MAX_PIE_SLICES);

    const sum = values => values.reduce((total, row) => total + row.value, 0);
    const otherValue = 100 - sum(slices);
    const unlistedValue = 100 - sum(sorted);
    if (otherValue >= 0.01) {
        const parts = [];
        if (otherRows.length > 0) {
            parts.push(otherRows.length + " more rows of the table");
        }
        if (unlistedValue >= 0.01) {
            parts.push("rows not shown in the table");
        }
        slices.push({label: "Other", value: otherValue, color: OTHER_SLICE_COLOR, details: [["Includes", parts.join(" and ")]]});
    }
    return slices;
}

/**
 * Calculates the arcs of the slices of a pie chart, starting at the top and clockwise.
 * The shares are scaled to the total, which may be a bit more than 100% because of rounding.
 *
 * @return {{startAngle: number, endAngle: number, midAngle: number, isFullCircle: boolean}[]}
 */
export function pieArcs(values) {
    const total = Math.max(100, values.reduce((sum, value) => sum + value, 0));
    let angle = -Math.PI / 2;
    return values.map(value => {
        const sweep = value / total * 2 * Math.PI;
        const arc = {startAngle: angle, endAngle: angle + sweep, midAngle: angle + sweep / 2, isFullCircle: sweep >= 2 * Math.PI - 1e-6};
        angle += sweep;
        return arc;
    });
}

/**
 * @return {string} SVG path of a pie slice.
 */
export function slicePath(arc, center, radius) {
    const x1 = center + radius * Math.cos(arc.startAngle);
    const y1 = center + radius * Math.sin(arc.startAngle);
    const x2 = center + radius * Math.cos(arc.endAngle);
    const y2 = center + radius * Math.sin(arc.endAngle);
    const largeArc = arc.endAngle - arc.startAngle > Math.PI ? 1 : 0;
    return `M${center},${center} L${x1},${y1} A${radius},${radius} 0 ${largeArc} 1 ${x2},${y2} Z`;
}

/**
 * Places labels centered above their x-positions, inside the width, and skips labels, which would overlap.
 *
 * @param {{x: number, width: number}[]} labels Labels sorted by their x-position.
 * @return {(number|null)[]} The center of each label, or null if the label is skipped.
 */
export function layoutLabels(labels, totalWidth, gap) {
    let lastEnd = -Infinity;
    return labels.map(({x, width}) => {
        const half = width / 2;
        const center = Math.min(Math.max(x, half), totalWidth - half);
        if (center - half <= lastEnd) {
            return null;
        }
        lastEnd = center + half + gap;
        return center;
    });
}

export const CHART_STYLES = ["lines", "bars"];

/**
 * Calculates the bars of a group of bars at one date.
 *
 * @param {number} seriesCount Number of bars in the group.
 * @param {number} slotWidth Available width for the group, e.g. the distance to the next date.
 * @param {{maxBarWidth: number, gap: number}} options Bars are at most maxBarWidth wide, with a gap between them.
 * @return {{width: number, offsets: number[]}} Width of each bar and the offset of its left edge to the center of the group.
 */
export function barLayout(seriesCount, slotWidth, {maxBarWidth, gap}) {
    if (seriesCount === 0) {
        return {width: 0, offsets: []};
    }
    // a group uses at most 70% of the available width, the rest separates the groups
    const groupWidth = slotWidth * 0.7;
    const width = Math.max(1, Math.min(maxBarWidth, (groupWidth - gap * (seriesCount - 1)) / seriesCount));
    const totalWidth = width * seriesCount + gap * (seriesCount - 1);
    const offsets = Array.from({length: seriesCount}, (_, index) => -totalWidth / 2 + index * (width + gap));
    return {width, offsets};
}

/**
 * The date axis of a chart ends with the period of the last value, so that the value of the current, incomplete
 * period, which is located at today, isn't at the right edge, e.g. the axis of the years shows the whole current year.
 *
 * @param {number} timestamp The date of the last value, in seconds.
 * @param {string} granularity The granularity of the chart, e.g. "yearEnd", "monthEnd", "month", "week" or "day".
 * @return {number} The end of the period of the date: the last day of its year, of its month or its week (Sunday).
 */
export function periodEnd(timestamp, granularity) {
    const date = new Date(timestamp * 1000);
    const [year, month, day] = [date.getUTCFullYear(), date.getUTCMonth(), date.getUTCDate()];
    switch (granularity) {
        case "yearEnd":
            return Date.UTC(year, 11, 31) / 1000;
        case "month":
        case "monthEnd":
            return Date.UTC(year, month + 1, 0) / 1000;
        case "week":
            return Date.UTC(year, month, day + (7 - date.getUTCDay()) % 7) / 1000;
        default:
            return timestamp;
    }
}

/**
 * The position of the cursor of a line chart at the closest value. A value at the edge of the chart, e.g. a point on
 * the y-axis, which is drawn outside of the plot by a few pixels, keeps the cursor at the edge, so that it's shown.
 * A value, which isn't drawn, e.g. outside of the dates of a zoomed chart, is replaced by the next value inside.
 *
 * @param {number} index The index of the closest value.
 * @param {(index: number) => number} positionOf The position of the value of an index on the x-axis, in pixels.
 * @param {number} count The number of values.
 * @param {number} width The width of the plot, in pixels.
 * @param {number} margin The distance from the plot, up to which values are drawn, e.g. the radius of the points.
 * @return {number} The position of the cursor, from 0 to the width.
 */
export function cursorPosition(index, positionOf, count, width, margin) {
    let position = positionOf(index);
    if (position < -margin && index + 1 < count) {
        position = positionOf(index + 1);
    } else if (position > width + margin && index > 0) {
        position = positionOf(index - 1);
    }
    return Math.min(Math.max(position, 0), width);
}

/**
 * @return {number} The smallest distance between the sorted timestamps, or the fallback if there's only one timestamp.
 */
export function smallestInterval(timestamps, fallback) {
    let smallest = Infinity;
    for (let i = 1; i < timestamps.length; i++) {
        smallest = Math.min(smallest, timestamps[i] - timestamps[i - 1]);
    }
    return smallest === Infinity ? fallback : smallest;
}

/**
 * @return {string} Key of a line chart, which identifies it across pages, e.g. to keep its style.
 */
export function lineChartKey(chart) {
    return chart.period + "/" + chart.units.join("+");
}

/**
 * The windows of the values of metrics, see ChartWindow.kt: sums and rates of the windows, which end at each date,
 * e.g. the sales of the 12 months before each date, at the end of a calendar period the period. The calendar windows
 * start at the first day of the period of each date, their values are the values of the tables, also of today.
 */
export const WINDOWS = ["follow", "day", "week", "month", "quarter", "year", "calendar-month", "calendar-quarter", "calendar-year"];

/**
 * The window, which follows the detail level of the chart: the calendar year at the level Year, the calendar month at
 * the level Month, the week and the day, see {@link shownWindow}. It's only used by the charts, /chart-data gets the
 * shown window.
 */
export const FOLLOW_WINDOW = "follow";

/** Labels of the windows in the charts. */
export const WINDOW_LABELS = {
    follow: "Follow the level", day: "Per day", week: "Last 7 days", month: "Last month", quarter: "Last 3 months", year: "Last 12 months",
    "calendar-month": "Calendar month", "calendar-quarter": "Calendar quarter", "calendar-year": "Calendar year",
};

/** The approximate length of the windows and of the steps between the dates of the detail levels, in days. */
const WINDOW_DAYS = {day: 1, week: 7, month: 28, quarter: 90, year: 365, "calendar-month": 28, "calendar-quarter": 90, "calendar-year": 365};
const DETAIL_DAYS = {day: 1, week: 7, month: 28, year: 365};

/** The window of a detail level, if the window of a chart is shorter than the steps of its dates. */
const DETAIL_WINDOWS = {day: "day", week: "week", month: "calendar-month", year: "calendar-year"};

/**
 * @return {boolean} If the window applies to the detail level: a window, which is shorter than the steps between the
 *  dates, would only show a part of each step, e.g. the sales of the last day of each year.
 */
export function windowApplies(window, detail) {
    return window === FOLLOW_WINDOW || (WINDOW_DAYS[window] ?? 0) >= (DETAIL_DAYS[detail] ?? 0);
}

/**
 * @return {string} The window of a line of a table: the window, which follows the level, if the table has the
 *  calendar window of its level, e.g. the calendar month of a table of months, so that the line follows the level.
 */
export function tableWindow(window, detail) {
    return window === DETAIL_WINDOWS[detail] ? FOLLOW_WINDOW : window;
}

/**
 * @return {{window: string, detail: string}} The level with the detail level, the window is replaced by the calendar
 *  window of the detail level, if it's too short, see {@link windowApplies}.
 */
export function withDetail(level, detail) {
    return {window: shownWindow(level.window, detail), detail};
}

/**
 * @param {string} window The selected window of a line, see {@link WINDOWS}.
 * @return {string} The shown window: the selected window, or the calendar window of the detail level for the window,
 *  which follows the level. A window, which is shorter than the steps of the dates, gets finer dates, see
 *  {@link lineDetail}.
 */
export function shownWindow(window, detail) {
    return window === FOLLOW_WINDOW ? DETAIL_WINDOWS[detail] : window;
}

/**
 * @param {string} window The shown window of a line, see {@link shownWindow}.
 * @param {string} detail The detail level of the chart.
 * @return {string} The detail level of the values of the line: the level of the chart, or a finer level, if the
 *  window is shorter than the steps of its dates, e.g. the values of each day of a line of the sales per day in a chart
 *  of months.
 */
export function lineDetail(window, detail) {
    return windowApplies(window, detail) ? detail : DETAILS.find(finer => windowApplies(window, finer)) ?? "day";
}

/** @return {string} The finest of the detail levels, e.g. "day" of "month" and "day". */
export function finestDetail(details) {
    return [...DETAILS].reverse().find(detail => details.includes(detail)) ?? "month";
}

/**
 * The detail levels of the charts of metrics, see ChartDetail.kt: the values at each day, at the end of each week,
 * of each month or of each year, and today.
 */
export const DETAILS = ["year", "month", "week", "day"];

/**
 * The series of metrics have this period, their values are loaded from /chart-data with the window and the detail
 * level of their chart, see {@link chartLevel}. Series of the same unit share a chart.
 */
export const METRIC_PERIOD = "metric";

/** The level of a new chart of metrics without the level of a table. */
const DEFAULT_LEVEL = {window: FOLLOW_WINDOW, detail: "month"};

/**
 * @param {{period: string, units: string[]}} chart A line chart of metrics.
 * @param {Object<string, {window: string, detail: string}>} levels The stored levels by the keys of the charts, also of
 *  each unit, see {@link chartLevelKeys}.
 * @return {{window: string, detail: string}} The shown window and detail level of the chart, the level of a combined
 *  chart is the level of its first unit until it's changed. A selected window, which is shorter than the steps of the
 *  dates, is replaced by the calendar window of the detail level, see {@link withDetail}.
 */
export function chartLevel(chart, levels) {
    const level = storedChartLevel(chart, levels);
    return withDetail(level, level.detail);
}

/**
 * @return {{window: string, detail: string}} The stored level of a chart with the selected window, which is kept, if a
 *  level is selected, to which the window doesn't apply, see {@link chartLevel}.
 */
export function storedChartLevel(chart, levels) {
    return levels[lineChartKey(chart)] ?? levels[lineChartKey({...chart, units: [chart.units[0]]})] ?? DEFAULT_LEVEL;
}

/**
 * @return {string[]} The keys of the levels of a chart: its key and the keys of its units, so that the level stays the
 *  same when units are combined in a chart or shown in their own charts.
 */
export function chartLevelKeys(chart) {
    const keys = chart.units.map(unit => lineChartKey({...chart, units: [unit]}));
    return [...new Set([lineChartKey(chart), ...keys])];
}

/**
 * @param {{label: string, usesWindow?: boolean}} series A series of a metric.
 * @param {string} window The window of the values, see {@link WINDOWS}.
 * @return {string} The label of the series with the window of sums and rates, e.g. "Trials, last 12 months".
 */
export function metricLabel(series, window) {
    return series.usesWindow ? `${series.label}, ${(WINDOW_LABELS[window] ?? window).toLowerCase()}` : series.label;
}

/**
 * @param {string} detail The detail level of a chart of metrics, see {@link DETAILS}.
 * @return {"month"|"year"} The period of the values, the dates of the chart are shown with it, values of days and
 *  weeks with their day, see {@link detailGranularity}.
 */
export function detailPeriod(detail) {
    return detail === "year" ? "year" : "month";
}

/**
 * @return {"day"|"week"|"monthEnd"|"yearEnd"} The granularity of a line chart of the detail level, see lineChart.js.
 */
export function detailGranularity(detail) {
    return {day: "day", week: "week", month: "monthEnd", year: "yearEnd"}[detail] ?? "monthEnd";
}

/**
 * @param {number} min The first timestamp of the date axis, in seconds.
 * @param {number} max The last timestamp of the date axis, in seconds.
 * @param {number|undefined} lastValue The timestamp of the last value, e.g. today for the value of the current year.
 * @return {number[]} The ticks of yearly values: the last day of each year, where the values of the years are
 *  located, and the last value of the current year, which is located at today.
 */
export function yearEndTicks(min, max, lastValue) {
    const ticks = [];
    const firstYear = new Date(min * 1000).getUTCFullYear();
    const lastYear = new Date(max * 1000).getUTCFullYear();
    for (let year = firstYear; year <= lastYear; year++) {
        const end = Date.UTC(year, 11, 31) / 1000;
        if (end >= min && end <= max) {
            ticks.push(end);
        }
    }
    const isNewYear = lastValue !== undefined && (ticks.length === 0
        || new Date(lastValue * 1000).getUTCFullYear() > new Date(ticks[ticks.length - 1] * 1000).getUTCFullYear());
    if (isNewYear && lastValue >= min && lastValue <= max) {
        ticks.push(lastValue);
    }
    return ticks;
}

/**
 * @param {{dates: string[], series: Object<string, {values: number[], incompleteFrom: number}>}} data
 *  The values of metrics, loaded from /chart-data.
 * @param {string} id The ID of a metric.
 * @return {{points: Map<number, number>, incomplete: Set<number>, partial: Set<number>}|null} The values of the metric,
 *  or null if there are none. The values start with the last zero before the first value, the data of all metrics
 *  starts with the first activity. Partial values only cover a part of their window, e.g. the first year of a plugin,
 *  which started in February.
 */
export function metricPoints(data, id) {
    const series = data?.series?.[id];
    if (!series) {
        return null;
    }
    const points = new Map();
    const incomplete = new Set();
    const partial = new Set();
    // values without data are null, e.g. the revenue retention of the first year
    const firstValue = series.values.findIndex(value => value !== 0 && value !== null);
    const start = firstValue === -1 ? 0 : Math.max(0, firstValue - 1);
    data.dates.slice(start).forEach((date, offset) => {
        const index = start + offset;
        const timestamp = isoDateToTimestamp(date);
        points.set(timestamp, series.values[index]);
        if (index >= series.incompleteFrom) {
            incomplete.add(timestamp);
        }
        if (index < (series.partialUntil ?? 0) && series.values[index] !== 0) {
            partial.add(timestamp);
        }
    });
    return {points, incomplete, partial};
}

/**
 * @param {number} timestamp Seconds since the epoch.
 * @param {number} years Number of years to add, negative to subtract.
 * @param {number} months Number of months to add, negative to subtract.
 * @return {number} The timestamp at the same day of the other month, at most at its last day, e.g. March 31 minus one
 *  month is the last day of February. The last day of a month moves to the last day of the other month, e.g.
 *  September 30 minus one month is August 31, because the values of months are located at their last day.
 */
export function shiftDate(timestamp, years, months) {
    const date = new Date(timestamp * 1000);
    const totalMonths = date.getUTCFullYear() * 12 + date.getUTCMonth() + years * 12 + months;
    const year = Math.floor(totalMonths / 12);
    const month = totalMonths - year * 12;
    const lastDay = new Date(Date.UTC(year, month + 1, 0)).getUTCDate();
    const isLastDay = date.getUTCDate() === new Date(Date.UTC(date.getUTCFullYear(), date.getUTCMonth() + 1, 0)).getUTCDate();
    const shifted = Date.UTC(year, month, isLastDay ? lastDay : Math.min(date.getUTCDate(), lastDay), date.getUTCHours(), date.getUTCMinutes(), date.getUTCSeconds());
    return shifted / 1000;
}

/**
 * The comparisons of the values at the cursor of a line chart with earlier and later values, in chronological order.
 * The cursor is between the earlier and the later comparisons.
 */
export const COMPARISONS = [
    {label: "−1 year", years: -1, months: 0},
    {label: "−1 month", years: 0, months: -1},
    {label: "+1 month", years: 0, months: 1},
    {label: "+1 year", years: 1, months: 0},
];

/**
 * A comparison needs values at least as frequent as its distance, e.g. yearly values can't be compared with the
 * previous month.
 *
 * @param {number} interval The smallest distance between the values in seconds.
 * @return {object[]} The comparisons of {@link COMPARISONS}, which are possible for the values.
 */
export function availableComparisons(interval) {
    // the shortest month has 28 days, the values of months are located at their last day
    return COMPARISONS.filter(({years, months}) => (Math.abs(years) * 365 + Math.abs(months) * 28) * SECONDS_PER_DAY >= interval * 0.9);
}

/**
 * @param {{timestamp: number, value: number}[]} points The values of a series, sorted by date.
 * @param {number} timestamp
 * @param {number} tolerance The maximum distance between the timestamp and the timestamp of the value.
 * @return {{timestamp: number, value: number}|null} The value closest to the timestamp.
 */
export function valueNear(points, timestamp, tolerance) {
    let low = 0;
    let high = points.length - 1;
    while (low < high) {
        const middle = (low + high) >> 1;
        if (points[middle].timestamp < timestamp) {
            low = middle + 1;
        } else {
            high = middle;
        }
    }
    const candidates = [points[low - 1], points[low]].filter(point => point !== undefined);
    const closest = candidates.reduce((best, point) => best === null
        || Math.abs(point.timestamp - timestamp) < Math.abs(best.timestamp - timestamp) ? point : best, null);
    return closest !== null && Math.abs(closest.timestamp - timestamp) <= tolerance ? closest : null;
}

/**
 * @return {{value: number, unit: "%"|"pp"}|null} The change from the earlier to the later value. Percentages change by
 *  percentage points, other values by percent. Null if a value is missing or the change of 0 can't be expressed in percent.
 */
export function valueChange(earlier, later, unit) {
    if (earlier === null || earlier === undefined || later === null || later === undefined) {
        return null;
    }
    if (unit === "%") {
        return {value: later - earlier, unit: "pp"};
    }
    if (earlier === 0) {
        return null;
    }
    return {value: (later - earlier) / Math.abs(earlier) * 100, unit: "%"};
}

/** Color of incomplete values of series, whose lighter color would be too similar to the color of the series. */
export const INCOMPLETE_GREY = "#a8a7a2";
/** {@link INCOMPLETE_GREY} of the dark mode. */
export const DARK_INCOMPLETE_GREY = "#5f5e66";

/**
 * @param {string} color A color like `#2a78d6`.
 * @param {boolean} [dark] If the color is shown in the dark mode. The color is faded towards the dark surface then.
 * @return {string} The color with slightly less saturation and more lightness, or less lightness in the dark mode,
 *  for incomplete values, which may still change. If it's too similar to the color, {@link INCOMPLETE_GREY} or
 *  {@link DARK_INCOMPLETE_GREY}. Other formats are returned unchanged.
 */
export function incompleteColor(color, dark = false) {
    const match = /^#([0-9a-f]{6})$/i.exec(color);
    if (match === null) {
        return color;
    }
    const [hue, saturation, lightness] = hexToHsl(match[1]);
    const result = hslToHex(hue, saturation * 0.8, dark ? lightness * 0.62 : lightness + (1 - lightness) * 0.3);
    // the distance of the RGB values, below about 70 the colors are hard to tell apart in thin lines and points
    const distance = Math.hypot(...[0, 2, 4].map(i => Number.parseInt(match[1].substring(i, i + 2), 16) - Number.parseInt(result.substring(i + 1, i + 3), 16)));
    if (distance < 70) {
        return dark ? DARK_INCOMPLETE_GREY : INCOMPLETE_GREY;
    }
    return result;
}

/** @return The hue, the saturation and the lightness of a color like `2a78d6`, from 0 to 1. */
function hexToHsl(hex) {
    const [r, g, b] = [0, 2, 4].map(i => Number.parseInt(hex.substring(i, i + 2), 16) / 255);
    const max = Math.max(r, g, b);
    const min = Math.min(r, g, b);
    const lightness = (max + min) / 2;
    let hue = 0;
    let saturation = 0;
    if (max !== min) {
        const delta = max - min;
        saturation = lightness > 0.5 ? delta / (2 - max - min) : delta / (max + min);
        hue = max === r ? (g - b) / delta + (g < b ? 6 : 0) : max === g ? (b - r) / delta + 2 : (r - g) / delta + 4;
        hue /= 6;
    }
    return [hue, saturation, lightness];
}

/**
 * Several averages of the same series, e.g. of 3 and 12 months, have different shades of the color of the series:
 * from a lighter shade for the first, e.g. the shortest average, to a darker shade for the last.
 *
 * @param {string} color The color of the series, like `#2a78d6`.
 * @param {number} index The position of the average among the averages of the series.
 * @param {number} count The number of averages of the series.
 * @return {string} The color of the average, the color of the series for a single average.
 */
export function averageShade(color, index, count) {
    const match = /^#([0-9a-f]{6})$/i.exec(color);
    if (match === null || count <= 1) {
        return color;
    }
    const [hue, saturation, lightness] = hexToHsl(match[1]);
    // from 1 for the lightest to -1 for the darkest shade
    const position = 1 - 2 * index / (count - 1);
    const shifted = position >= 0 ? lightness + (1 - lightness) * 0.35 * position : lightness * (1 + 0.35 * position);
    return hslToHex(hue, saturation, shifted);
}

function hslToHex(hue, saturation, lightness) {
    const a = saturation * Math.min(lightness, 1 - lightness);
    const channel = n => {
        const k = (n + hue * 12) % 12;
        const value = lightness - a * Math.max(-1, Math.min(k - 3, 9 - k, 1));
        return Math.round(value * 255).toString(16).padStart(2, "0");
    };
    return "#" + channel(0) + channel(8) + channel(4);
}

/**
 * Incomplete values are ignored, e.g. the sales of the current month are often the lowest value.
 *
 * @param {number[]} timestamps
 * @param {(number|null)[]} values The values at the timestamps, null if there's no value.
 * @param {Set<number>} incomplete The timestamps of incomplete values.
 * @param {[number, number]|null} [range] Only values within the range of timestamps, e.g. of the shown dates.
 * @return {{min: number, max: number}|null} The indices of the first minimum and the first maximum,
 *  or null if there are no two different values.
 */
export function extremeIndices(timestamps, values, incomplete, range = null) {
    let min = null;
    let max = null;
    values.forEach((value, index) => {
        const isOutside = range !== null && (timestamps[index] < range[0] || timestamps[index] > range[1]);
        if (value === null || value === undefined || isOutside || incomplete.has(timestamps[index])) {
            return;
        }
        if (min === null || value < values[min]) {
            min = index;
        }
        if (max === null || value > values[max]) {
            max = index;
        }
    });
    return min === null || values[min] === values[max] ? null : {min, max};
}

/**
 * @param {{points: Map<number, number>, incomplete: Set<number>}} series
 * @return {object} The series without its incomplete values.
 */
export function withoutIncomplete(series) {
    return {
        ...series,
        points: new Map([...series.points].filter(([timestamp]) => !series.incomplete.has(timestamp))),
        incomplete: new Set(),
    };
}

/**
 * Trends, which can be added to line charts: moving averages of 3 and 12 months and the values of the previous year.
 */
export const OVERLAYS = ["average1", "average3", "average12", "previousYear"];

/**
 * @param {{measure?: string|null, unit: string}} series
 * @return {"flow"|"stock"|"ratio"|null} What the values measure: sums of a period, values at the end of a period or
 *  ratios. Percentages without a measure are ratios, other series are unknown.
 */
export function seriesMeasure(series) {
    return series.measure ?? (series.unit === "%" ? "ratio" : null);
}

/** The months of the moving averages. */
export const AVERAGE_MONTHS = {average1: 1, average3: 3, average12: 12};

/**
 * The moving averages of metrics are loaded from /chart-data, see ChartMetricValues.average: values at a date can
 * always be averaged, sums and rates only if the months are longer than the window. Of series of the values of tables,
 * the 3- and 12-month averages only apply to monthly values. The previous year can be compared for all values, except
 * yearly values.
 *
 * @param {string} kind One of {@link OVERLAYS}.
 * @param {{period: string, metric?: string, usesWindow?: boolean}} series
 * @param {string|null} detail The detail level of a chart of metrics, see {@link DETAILS}.
 * @param {string|null} [window] The window of a chart of metrics, see {@link WINDOWS}.
 */
export function overlayApplies(kind, series, detail, window = null) {
    if (series.metric) {
        if (kind === "previousYear") {
            return detail !== "year";
        }
        return !series.usesWindow || AVERAGE_MONTHS[kind] * WINDOW_DAYS.month > (WINDOW_DAYS[window] ?? Infinity);
    }
    switch (kind) {
        case "previousYear":
            return series.period !== "year";
        case "average1":
            return false;
        default:
            return series.period === "month";
    }
}

function completePoints(series) {
    return [...series.points]
        .filter(([timestamp, value]) => !series.incomplete.has(timestamp) && value !== null && value !== undefined)
        .sort(([a], [b]) => a - b);
}

/**
 * The average of the value of each month and the values of the previous months. Incomplete values are ignored.
 * There's no average if one of the months has no value, missing months aren't counted as zero.
 *
 * @param {{points: Map<number, number>, incomplete: Set<number>}} series Values at the end of each month.
 * @param {number} months The number of months of each average.
 * @return {Map<number, number>} The averages at the dates of the values.
 */
export function trailingAverage(series, months) {
    const points = completePoints(series);
    const result = new Map();
    for (let i = months - 1; i < points.length; i++) {
        const window = points.slice(i - months + 1, i + 1);
        // the first value of the window must be at the end of the first month of the window
        if (window[0][0] >= shiftDate(points[i][0], 0, -(months - 1)) - SECONDS_PER_DAY) {
            result.set(points[i][0], window.reduce((sum, [, value]) => sum + value, 0) / months);
        }
    }
    return result;
}

/**
 * @param {{points: Map<number, number>, incomplete: Set<number>}} series
 * @return {{points: Map<number, number>, incomplete: Set<number>}} The values of the series one year later, to
 *  compare them with the values of the series. Values after the last value of the series are dropped.
 */
export function previousYear(series) {
    const lastTimestamp = Math.max(...series.points.keys());
    const points = new Map();
    const incomplete = new Set();
    for (const [timestamp, value] of series.points) {
        const shifted = shiftDate(timestamp, 1, 0);
        if (shifted <= lastTimestamp) {
            points.set(shifted, value);
            if (series.incomplete.has(timestamp)) {
                incomplete.add(shifted);
            }
        }
    }
    return {points, incomplete};
}

/** Kinds of vertical lines in the line charts. */
export const MARKER_KINDS = ["release", "price", "ide"];

/** Labels of the kinds of vertical lines. */
export const MARKER_LABELS = {release: "Product version", price: "Price changes", ide: "IntelliJ IDEA releases", custom: "Events"};

/**
 * The items of the menu "Events" of the chart dialog: the custom events of the page first, then a separator and the
 * kinds of vertical lines of the page, e.g. the releases.
 *
 * @param {object[]} customEvents The custom events of the page.
 * @param {string[]} markerKinds The kinds of vertical lines of the page, see {@link MARKER_KINDS}.
 * @return {({type: "event", event: object}|{type: "separator"}|{type: "marker", kind: string})[]} The items, empty
 *  without events and kinds.
 */
export function eventMenuItems(customEvents, markerKinds) {
    const events = customEvents.map(event => ({type: "event", event}));
    const markers = markerKinds.map(kind => ({type: "marker", kind}));
    const separator = events.length > 0 && markers.length > 0 ? [{type: "separator"}] : [];
    return [...events, ...separator, ...markers];
}

/**
 * @param {string[]} shownKinds The kinds of the shown vertical lines, see {@link MARKER_KINDS}.
 * @param {string[]} shownEvents The IDs of the shown custom events, they are selected one by one.
 * @return {object[]} The markers of the shown kinds and the shown custom events, sorted by date, as needed to place
 *  their labels.
 */
export function visibleMarkers(markers, shownKinds, shownEvents = []) {
    return markers
        .filter(marker => marker.kind === "custom" ? shownEvents.includes(marker.id) : shownKinds.includes(marker.kind))
        .sort((a, b) => a.timestamp - b.timestamp);
}

/**
 * Places the entries of a legend in rows, an entry moves to the next row if it doesn't fit into the width.
 *
 * @param {number[]} widths Widths of the entries.
 * @param {number} maxWidth Available width of a row.
 * @param {number} gap Horizontal space between the entries of a row.
 * @return {{x: number, row: number}[]} The position of each entry.
 */
export function layoutLegend(widths, maxWidth, gap) {
    let x = 0;
    let row = 0;
    return widths.map(width => {
        if (x > 0 && x + width > maxWidth) {
            x = 0;
            row++;
        }
        const position = {x, row};
        x += width + gap;
        return position;
    });
}

/**
 * @param {string[]} parts Parts of the name, e.g. the title of the page and the title of the chart.
 * @param {string} extension File extension without the dot.
 * @return {string} A file name of only letters, digits and dashes.
 */
export function exportFileName(parts, extension) {
    const name = parts
        .map(part => part.normalize("NFKD").replace(/[\u0300-\u036f]/g, "").toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-+|-+$/g, ""))
        .filter(part => part.length > 0)
        .join("-");
    return (name || "chart") + "." + extension;
}
