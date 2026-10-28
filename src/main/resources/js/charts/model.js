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
 * The smallest distance of a new color to the colors of its chart, which is enough to tell them apart, see
 * {@link colorDistance}. It accepts e.g. orange next to a blue line, but not green or orange lines over blue bars.
 */
export const MIN_COLOR_DISTANCE = 0.22;

/**
 * Picks the color of a new series: the first color of the palette, which isn't used yet and is distinct from the
 * colors of the other series of its chart, otherwise the unused color, which is the most distinct from them.
 *
 * @param {string[]} usedColors The colors of all series.
 * @param {string} [preferredColor] The stored color of the series, which is kept if it isn't used yet.
 * @param {{color: string, style: "lines"|"bars"}[]} [neighbors] The other series of the chart of the new series.
 * @param {"lines"|"bars"} [style] How the new series is drawn.
 * @return {string|undefined} The color, undefined if all colors are used.
 */
export function nextColor(usedColors, preferredColor = undefined, neighbors = [], style = "lines") {
    const used = new Set(usedColors);
    if (preferredColor !== undefined && SERIES_COLORS.includes(preferredColor) && !used.has(preferredColor)) {
        return preferredColor;
    }
    const unused = SERIES_COLORS.filter(color => !used.has(color));
    const scores = unused.map(color => Math.min(...neighbors.map(n => colorDistance(color, n.color, style !== n.style))));
    const firstDistinct = unused.find((_, index) => scores[index] >= MIN_COLOR_DISTANCE);
    // the first of equal scores is the earliest color of the palette
    return firstDistinct ?? unused[scores.indexOf(Math.max(...scores))];
}

/** Simulations of the color vision deficiencies deuteranopia and protanopia in linear RGB (Machado et al. 2009). */
const VISION_DEFICIENCIES = [
    [[0.367322, 0.860646, -0.227968], [0.280085, 0.672501, 0.047413], [-0.011820, 0.042940, 0.968881]],
    [[0.152286, 1.052583, -0.204868], [0.114503, 0.786281, 0.099216], [-0.003882, -0.048116, 1.051998]],
];

/**
 * The distance of two colors in the Oklab color space, the smallest of normal vision and of the simulated color
 * vision deficiencies. A thin line over a bar needs a difference of lightness, not only of hue, e.g. a green line is
 * hard to see on a blue bar, so the distance of a line and a bar is at most twice the difference of their lightness.
 *
 * @param {string} first A color like `#2a78d6`.
 * @param {string} second A color like `#2a78d6`.
 * @param {boolean} lineOverBar If one color is a line and the other a bar.
 * @return {number} The distance, about 0 to 0.5 for the colors of the palette.
 */
export function colorDistance(first, second, lineOverBar = false) {
    const [a, b] = [first, second].map(linearRgb);
    const distances = [a, b].map(oklab);
    let distance = Math.hypot(...distances[0].map((value, i) => value - distances[1][i]));
    for (const matrix of VISION_DEFICIENCIES) {
        const [x, y] = [a, b].map(rgb => oklab(matrix.map(row => Math.min(1, Math.max(0, row[0] * rgb[0] + row[1] * rgb[1] + row[2] * rgb[2])))));
        distance = Math.min(distance, Math.hypot(...x.map((value, i) => value - y[i])));
    }
    return lineOverBar ? Math.min(distance, 2 * Math.abs(distances[0][0] - distances[1][0])) : distance;
}

/** @return {number[]} The linear RGB values of a color like `#2a78d6`, from 0 to 1. */
function linearRgb(color) {
    return [1, 3, 5].map(i => {
        const value = Number.parseInt(color.substring(i, i + 2), 16) / 255;
        return value <= 0.04045 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4;
    });
}

/** @return {number[]} The lightness and the a and b values of the Oklab color space of linear RGB values. */
function oklab([r, g, b]) {
    const l = Math.cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b);
    const m = Math.cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b);
    const s = Math.cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b);
    return [
        0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
        1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
        0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s,
    ];
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
    return paddedRange(min, max);
}

/**
 * @return {[number, number]|null} The range of the dates, null without dates. A single date needs a range to be shown.
 */
function paddedRange(min, max) {
    if (min === Infinity) {
        return null;
    }
    return min === max ? [min - SECONDS_PER_DAY, max + SECONDS_PER_DAY] : [min, max];
}

/**
 * @param {number[]} timestamps The dates of the values.
 * @param {(number|null|undefined)[][]} valuesList The values of each line at the dates.
 * @param {boolean[]} shown If each line is shown, e.g. not hidden by a click on the legend.
 * @return {[number, number]|null} The first and the last date with a value of a shown line, like {@link dateRange},
 *  null without values of shown lines.
 */
export function shownDateRange(timestamps, valuesList, shown) {
    let min = Infinity;
    let max = -Infinity;
    valuesList.forEach((values, line) => {
        if (!shown[line]) {
            return;
        }
        values.forEach((value, index) => {
            if (value !== null && value !== undefined) {
                min = Math.min(min, timestamps[index]);
                max = Math.max(max, timestamps[index]);
            }
        });
    });
    return paddedRange(min, max);
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

/** The ranges of the menu "Range", which end today. They're stored with their name and get new dates each day. */
export const RANGE_PRESETS = {"today": "Today", "past-7-days": "Past 7 days", "this-month": "This month", "this-year": "This year"};

/**
 * @param {string} preset A key of {@link RANGE_PRESETS}.
 * @param {Date} [now] The current time, the dates are the days of the time zone of the browser.
 * @return {DateRange|null} The dates of the preset until today, with the preset, null for an unknown preset.
 */
export function rangeOfPreset(preset, now = new Date()) {
    const day = date => [date.getFullYear(), date.getMonth() + 1, date.getDate()].map(n => String(n).padStart(2, "0")).join("-");
    const today = day(now);
    switch (preset) {
        case "today":
            return {from: today, to: today, preset};
        case "past-7-days":
            return {from: day(new Date(now.getFullYear(), now.getMonth(), now.getDate() - 6)), to: today, preset};
        case "this-month":
            return {from: day(new Date(now.getFullYear(), now.getMonth(), 1)), to: today, preset};
        case "this-year":
            return {from: day(new Date(now.getFullYear(), 0, 1)), to: today, preset};
        default:
            return null;
    }
}

/**
 * The items of the menu "Range": all dates, the ranges until today, the heading "Years" and the years with values,
 * the latest year first. A zoomed range, which is neither a year nor a range until today, is the last item.
 *
 * @param {number[]} years The years with values, see {@link rangeYears}.
 * @param {DateRange|null} range The shown dates, null for all dates.
 * @param {Date} [now] The current time, for the dates of the ranges until today.
 * @return {({label: string, range: DateRange|null}|{heading: string})[]} The items, a heading has no range.
 */
export function rangeMenuItems(years, range, now = new Date()) {
    const items = [
        {label: "All", range: null},
        ...Object.entries(RANGE_PRESETS).map(([preset, label]) => ({label, range: rangeOfPreset(preset, now)})),
        ...(years.length > 0 ? [{heading: "Years"}] : []),
        ...years.map(year => ({label: String(year), range: rangeOfYear(year)})),
    ];
    const label = rangeLabel(range);
    if (!items.some(item => item.label === label)) {
        items.push({label, range});
    }
    return items;
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
    if (range.preset && RANGE_PRESETS[range.preset]) {
        return RANGE_PRESETS[range.preset];
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
    return raw > 0 ? niceCeil(raw) : 1;
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
 * @param {(number|null|undefined)[]} values The values of a series.
 * @return {(number|null|undefined)[]} The values on a logarithmic y-axis: values of 0 and below have no position on it,
 *  they aren't drawn. The value table and the tooltip still show them.
 */
export function logScaleValues(values) {
    return values.map(value => value === null || value === undefined || value > 0 ? value : null);
}

/**
 * @param {number|null} min The lowest drawn value, see {@link logScaleValues}.
 * @param {number|null} max The highest drawn value.
 * @return {[number, number]} The range of a logarithmic y-axis: the powers of ten around the values, e.g. 10 to 1000
 *  for values from 12 to 830, 1 to 10 without values.
 */
export function logRange(min, max) {
    if (!(max > 0)) {
        return [1, 10];
    }
    const low = 10 ** Math.floor(Math.log10(min > 0 ? min : max));
    const high = 10 ** Math.ceil(Math.log10(max));
    return [low, high > low ? high : low * 10];
}

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
 * @param {string|null} [levelWindow] The calendar window of the level of the chart, e.g. `calendar-month` at the
 *  level Month. Lines with this window have no suffix, it's the window of the dates.
 * @return {string} The label of the series with the window of sums and rates, e.g. "Trials, last 12 months".
 */
export function metricLabel(series, window, levelWindow = null) {
    return series.usesWindow && window !== levelWindow ? `${series.label}, ${(WINDOW_LABELS[window] ?? window).toLowerCase()}` : series.label;
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
 * @return {number[]} The ticks of yearly values at the values, so that the tick and the tooltip of a value show the
 *  same year: the last day of each year until the last value, and the last value of the current year, which is
 *  located at today. The axis may end after the last value, at the end of the current year, which has no tick.
 */
export function yearEndTicks(min, max, lastValue) {
    const ticks = [];
    for (let year = new Date(min * 1000).getUTCFullYear(); year <= new Date(max * 1000).getUTCFullYear(); year++) {
        const end = Date.UTC(year, 11, 31) / 1000;
        if (end >= min && end <= max && (lastValue === undefined || end <= lastValue)) {
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
 *  compare them with the values of the series. The whole previous year is kept, also after the last value of the
 *  series, e.g. the months after the current month, the values after the end of the year of the last value are
 *  dropped.
 */
export function previousYear(series) {
    const lastTimestamp = periodEnd(Math.max(...series.points.keys()), "yearEnd");
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

/**
 * @param {{from: string}|null} range The shown dates of the charts, null for all dates.
 * @param {string[]} shownOverlays The shown trends, see {@link OVERLAYS}.
 * @return {string|null} The first date of the values, which are loaded, ISO: the start of the range, a year earlier
 *  with the trend of the previous year, whose values are the values of the year before the range, see
 *  {@link previousYear}. Null for all dates.
 */
export function loadedRangeStart(range, shownOverlays) {
    if (!range?.from) {
        return null;
    }
    if (!shownOverlays.includes("previousYear")) {
        return range.from;
    }
    const [year, month, day] = range.from.split("-").map(Number);
    // February 29 of the previous year is February 28
    const lastDay = new Date(Date.UTC(year - 1, month, 0)).getUTCDate();
    return [year - 1, month, Math.min(day, lastDay)].map((n, i) => String(n).padStart(i === 0 ? 4 : 2, "0")).join("-");
}

/** Kinds of vertical lines in the line charts. */
export const MARKER_KINDS = ["release", "price", "continuity", "ide"];

/** Labels of the kinds of vertical lines. */
export const MARKER_LABELS = {release: "Product version", price: "Price changes", continuity: "Continuity discount", ide: "IntelliJ IDEA releases", custom: "Events"};

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

/** The styles of a line in its menu, `null` is the style of the chart, see the buttons Lines/Bars of the chart. */
export const SERIES_STYLES = [[null, "Like the chart"], ["lines", "Line"], ["bars", "Bars"]];

/**
 * @param {{average?: number, style?: "lines"|"bars"}} series A line of a chart.
 * @param {"lines"|"bars"} chartStyle The style of the chart.
 * @return {"lines"|"bars"} How the line is drawn: its style or the style of the chart. A moving average is always a line.
 */
export function seriesStyle(series, chartStyle) {
    return series.average ? "lines" : series.style ?? chartStyle;
}

/**
 * The sections of the menu of a line in the legend: its window, if its values depend on the window, and its type,
 * unless it's a moving average, which is always a line.
 *
 * @param {{usesWindow?: boolean, window?: string|null, selectedWindow?: string, average?: number, style?: "lines"|"bars"}} series
 *  A line with its shown and its selected window.
 * @param {boolean} canChangeWindow If the chart allows to select the windows of its lines, e.g. a chart of metrics.
 * @return {{kind: "window"|"style", title: string, items: {value: string|null, label: string, isChecked: boolean}[]}[]}
 *  The sections, empty without options.
 */
export function seriesMenuSections(series, canChangeWindow) {
    // the values of the parameters of a metric of a family, e.g. the release of the downloads of a release
    const sections = [...(series.parameterSections ?? [])];
    if (canChangeWindow && series.usesWindow && series.window) {
        const selected = series.selectedWindow ?? series.window;
        // e.g. the unique downloads only have the windows of a day, a week and a calendar month
        const windows = WINDOWS.filter(window => allowedWindow(window, series.windows) === window);
        sections.push({kind: "window", title: "Window", items: windows.map(window => ({value: window, label: WINDOW_LABELS[window], isChecked: window === selected}))});
    }
    if (!series.average) {
        const style = series.style ?? null;
        sections.push({kind: "style", title: "Type", items: SERIES_STYLES.map(([value, label]) => ({value, label, isChecked: value === style}))});
    }
    return sections;
}

/**
 * @param {string} window A window, see {@link WINDOWS}.
 * @param {string[]|null|undefined} windows The windows, which a metric has values of, e.g. the unique downloads, null
 *  for all windows.
 * @return {string} The window, or the window, which follows the level, if the metric has no values of the window.
 */
export function allowedWindow(window, windows) {
    return !windows || window === FOLLOW_WINDOW || windows.includes(window) ? window : FOLLOW_WINDOW;
}

/**
 * @param {string} metricId The ID of a metric, e.g. `release-downloads.1234.IIU`.
 * @param {{id: string, parameters?: object[]}[]} families The families of metrics with parameters of /chart-metrics.
 * @return {{family: object, values: (string|null)[]}|null} The family of a metric of a family and the values of its
 *  parameters, `null` for missing optional values, or null for other metrics.
 */
export function familyOfMetric(metricId, families) {
    const family = families.find(f => f.parameters && metricId.startsWith(f.id + "."));
    if (!family) {
        return null;
    }
    const values = metricId.slice(family.id.length + 1).split(".");
    if (values.length > family.parameters.length || values.some(value => value === "")) {
        return null;
    }
    return {family, values: family.parameters.map((_, index) => values[index] ?? null)};
}

/**
 * @param {string} familyId The ID of a family of metrics, e.g. `release-downloads`.
 * @param {(string|null)[]} values The values of its parameters, null for missing optional values.
 * @return {string} The ID of the metric, e.g. `release-downloads.1234`, missing optional values are left out.
 */
export function familyMetricId(familyId, values) {
    const present = [...values];
    while (present.length > 0 && present[present.length - 1] === null) {
        present.pop();
    }
    return [familyId, ...present].join(".");
}

/**
 * @param {{family: object, values: (string|null)[]}} familyMetric A metric of a family, see familyOfMetric.
 * @param {Object<string, {value: string, label: string}[]>} parameterValues The loaded values of the parameters by
 *  `<family>/<parameter>`, see parameterValuesKey. Parameters without loaded values have no section.
 * @return {{kind: string, title: string, items: {value: string|null, label: string, isChecked: boolean}[]}[]} The
 *  sections of the menu of a line, which select the values of the parameters, e.g. the release and the product. The
 *  kind is `parameter:<index>`, an optional parameter has the item of all values first.
 */
export function parameterSections({family, values}, parameterValues) {
    return family.parameters.flatMap((parameter, index) => {
        const loaded = parameterValues[parameterValuesKey(family.id, parameter.id)];
        if (!loaded) {
            return [];
        }
        const items = loaded.map(({value, label}) => ({value, label, isChecked: value === values[index]}));
        if (parameter.allLabel) {
            items.unshift({value: null, label: parameter.allLabel, isChecked: values[index] === null});
        }
        return [{kind: "parameter:" + index, title: parameter.title, items}];
    });
}

/** @return {string} The key of the values of a parameter of a family of metrics, e.g. `release-downloads/release`. */
export function parameterValuesKey(familyId, parameterId) {
    return familyId + "/" + parameterId;
}

/**
 * @param {{metric: string, window?: string|null, usesWindow?: boolean, average?: number}} series A line of a metric
 *  with its shown window, see ChartController.metricChartSeries.
 * @param {boolean} [ofPreviousYear] If the line has the values one year before each date.
 * @return {string} The line in /chart-export, e.g. `sales@avg3:calendar-month` or `mrr@previous-year`, see
 *  ChartExport.kt. Values at a date have no window.
 */
export function exportLineId(series, ofPreviousYear = false) {
    return series.metric
        + (series.average ? `@avg${series.average}` : "")
        + (ofPreviousYear ? "@previous-year" : "")
        + (series.usesWindow && series.window ? `:${series.window}` : "");
}

/** Characters, which are allowed in a query and keep the address of /chart-export readable, e.g. for an AI agent. */
const READABLE_QUERY_CHARACTERS = /%(2C|3A|40)/g;

/**
 * The address of the values of the shown lines of a chart of metrics as CSV, e.g. to give it to an AI agent: the
 * lines, which aren't hidden, with their windows and moving averages, the lines of the previous year, the detail
 * level, the selected dates and the incomplete values, if they're shown. See ChartExport.kt.
 *
 * @param {string} pageUrl The address of the page, its origin and its plugin are used.
 * @param {object} chart
 * @param {object[]} chart.series The shown lines of metrics of the chart, see ChartController.metricChartSeries.
 * @param {string} chart.detail The detail level of the chart, see {@link DETAILS}.
 * @param {string[]} chart.shownOverlays The shown trends, see {@link OVERLAYS}.
 * @param {Set<string>} chart.hiddenSeries The keys of the hidden lines and trends (`<series>:<kind>`).
 * @param {DateRange|null} chart.dateRange The selected dates, null for all dates.
 * @param {boolean} chart.showIncomplete If incomplete values are shown.
 * @return {string|null} The address, null if the chart has no shown lines.
 */
export function chartExportUrl(pageUrl, {series, detail, shownOverlays, hiddenSeries, dateRange, showIncomplete}) {
    const lines = series.flatMap(s => {
        const ofPreviousYear = shownOverlays.includes("previousYear") && overlayApplies("previousYear", s, detail, s.window)
            && !hiddenSeries.has(`${s.key}:previousYear`);
        return [
            ...(hiddenSeries.has(s.key) ? [] : [exportLineId(s)]),
            ...(ofPreviousYear ? [exportLineId(s, true)] : []),
        ];
    });
    if (lines.length === 0) {
        return null;
    }
    const page = new URL(pageUrl);
    const pluginId = page.searchParams.get("pluginId");
    const parameters = [
        ...(pluginId !== null ? [["pluginId", pluginId]] : []),
        ["detail", detail],
        ["lines", [...new Set(lines)].join(",")],
        ...(dateRange ? [["from", dateRange.from], ["to", dateRange.to]] : []),
        ...(showIncomplete ? [] : [["incomplete", "false"]]),
    ];
    const query = parameters
        .map(([name, value]) => name + "=" + encodeURIComponent(value).replace(READABLE_QUERY_CHARACTERS, decodeURIComponent))
        .join("&");
    return `${page.origin}/chart-export?${query}`;
}
