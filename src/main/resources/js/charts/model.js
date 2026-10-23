/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

/**
 * Pure functions for the data of the charts. They don't access the DOM and are tested with Node.js.
 */

import {isAmountUnit, PERIODS, SECONDS_PER_DAY} from "./format.js";

/** Categorical palette, assigned in fixed order. */
export const SERIES_COLORS = ["#2a78d6", "#eb6834", "#1baf7a", "#eda100", "#e87ba4", "#008300", "#6250d6", "#e34948"];
export const OTHER_SLICE_COLOR = "#a8a7a2";
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
