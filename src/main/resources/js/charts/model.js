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

/**
 * Granularities of the line charts of monthly values. Columns with values for each day, e.g. MRR, can be shown per
 * week or per day. Their values have the scale of the monthly values, see OverviewDailyValues.kt.
 */
export const GRANULARITIES = ["month", "week", "day"];

/**
 * @param {{period: string, daily: string|null}[]} seriesList The series of a line chart.
 * @return {boolean} If the chart can show the values per week or per day.
 */
export function hasFinerValues(seriesList) {
    return seriesList.some(series => series.period === "month" && series.daily);
}

/**
 * @param {{dates: string[], series: Object<string, {values: number[], incompleteFrom: number}>}} data
 *  The values per day or per week, loaded from the server.
 * @param {string} id The ID of the values of a column.
 * @return {{points: Map<number, number>, incomplete: Set<number>}|null} The values of the column, or null if there are none.
 *  The values start with the last zero before the first value, the data of all columns starts with the first activity.
 */
export function finerPoints(data, id) {
    const series = data?.series?.[id];
    if (!series) {
        return null;
    }
    const points = new Map();
    const incomplete = new Set();
    const firstValue = series.values.findIndex(value => value !== 0);
    const start = firstValue === -1 ? 0 : Math.max(0, firstValue - 1);
    data.dates.slice(start).forEach((date, offset) => {
        const index = start + offset;
        const timestamp = isoDateToTimestamp(date);
        points.set(timestamp, series.values[index]);
        if (index >= series.incompleteFrom) {
            incomplete.add(timestamp);
        }
    });
    return {points, incomplete};
}

/**
 * @param {number} timestamp Seconds since the epoch.
 * @param {number} years Number of years to add, negative to subtract.
 * @param {number} months Number of months to add, negative to subtract.
 * @return {number} The timestamp at the same day of the other month, at most at its last day, e.g. March 31 minus one
 *  month is the last day of February. The values of months are located at their last day.
 */
export function shiftDate(timestamp, years, months) {
    const date = new Date(timestamp * 1000);
    const totalMonths = date.getUTCFullYear() * 12 + date.getUTCMonth() + years * 12 + months;
    const year = Math.floor(totalMonths / 12);
    const month = totalMonths - year * 12;
    const lastDay = new Date(Date.UTC(year, month + 1, 0)).getUTCDate();
    const shifted = Date.UTC(year, month, Math.min(date.getUTCDate(), lastDay), date.getUTCHours(), date.getUTCMinutes(), date.getUTCSeconds());
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

/** Kinds of vertical lines in the line charts. */
export const MARKER_KINDS = ["release", "price", "ide"];

/** Labels of the kinds of vertical lines. */
export const MARKER_LABELS = {release: "Product version", price: "Price changes", ide: "IntelliJ IDEA releases"};

/**
 * @param {string[]} shownKinds The kinds of vertical lines to show, they are hidden by default.
 * @return {object[]} The markers of the shown kinds, sorted by date, as needed to place their labels.
 */
export function visibleMarkers(markers, shownKinds) {
    return markers.filter(marker => shownKinds.includes(marker.kind)).sort((a, b) => a.timestamp - b.timestamp);
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
