/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

/**
 * Line charts rendered with uPlot, which is loaded as a global script before this module.
 */

import {formatDate, SECONDS_PER_DAY, unitTitle} from "./format.js";
import {alignSeries, availableComparisons, barLayout, layoutLabels, MARKER_LABELS, shortestPeriod, shiftDate, smallestInterval, valueChange, valueNear, zeroBasedRange} from "./model.js";

export const CHART_STYLE = {
    axisColor: "#52514e",
    gridColor: "#e4e3df",
    surfaceColor: "#ffffff",
    // values, which may still change, use the color of incomplete values in the tables
    incompleteColor: "#6b6b6b",
    // vertical lines: line color and dash pattern of each kind, the labels use the color of the axes
    markerWidth: 2,
    markers: {
        release: {color: "#7d7c77", dash: [4, 4]},
        price: {color: "#c27c0e", dash: [2, 3]},
        ide: {color: "#6f74b8", dash: [8, 3]},
    },
    pointRadius: 3,
    // series with more values, e.g. per day, only show the points of incomplete values, smaller and without a ring
    maxPointsShown: 120,
    densePointRadius: 2,
    pointRing: 2,
    // bars are thin, with a gap between neighbors and a rounded end at the value
    maxBarWidth: 24,
    barGap: 2,
    barRadius: 4,
};

/** The series and vertical lines of each chart, needed for the legend of the exported images. */
const legendData = new WeakMap();

/**
 * The legend of an exported image: the shown series, incomplete values and the kinds of vertical lines in the date range.
 * Unlike the legend of uPlot, it shows no values.
 *
 * @param {uPlot} plot A chart created by {@link createLineChart}.
 * @return {{label: string, color: string, shape: "line"|"box"|"point"|"marker", dash?: number[]}[]}
 */
export function lineChartLegend(plot) {
    const {chartSeries, markers, isBars} = legendData.get(plot);
    const min = plot.scales.x.min;
    const max = plot.scales.x.max;
    const shown = chartSeries.filter((series, index) => plot.series[index + 1].show);
    const entries = shown.map(series => ({
        label: series.isRightAxis ? series.label + " (right axis)" : series.label,
        color: series.color,
        shape: isBars ? "box" : "line",
    }));
    if (shown.some(series => [...series.incomplete].some(timestamp => timestamp >= min && timestamp <= max))) {
        entries.push({label: "Incomplete values", color: CHART_STYLE.incompleteColor, shape: isBars ? "box" : "point"});
    }
    const kinds = new Set(markers.filter(marker => marker.timestamp >= min && marker.timestamp <= max).map(marker => marker.kind));
    for (const kind of kinds) {
        const style = CHART_STYLE.markers[kind] ?? CHART_STYLE.markers.release;
        entries.push({label: MARKER_LABELS[kind] ?? kind, color: style.color, dash: style.dash, shape: "marker"});
    }
    return entries;
}

/**
 * @param {string} fontFamily The font of the page. uPlot draws on a canvas, which doesn't use the CSS fonts.
 */
function fonts(fontFamily) {
    return {
        ticks: "12px " + fontFamily,
        axisTitle: "12px " + fontFamily,
        releaseLabel: "11px " + fontFamily,
    };
}

function scaledFont(font, pixelRatio) {
    return font.replace(/^(\d+)px/, (match, size) => (size * pixelRatio) + "px");
}

/**
 * Draws a vertical line with a centered label above the plot for each marker in the date range, e.g. major releases.
 * The lines are drawn before the series, so that they don't cover the data.
 */
function drawMarkers(plot, markers, font) {
    const releases = markers.filter(marker => marker.timestamp >= plot.scales.x.min && marker.timestamp <= plot.scales.x.max);
    if (releases.length === 0) {
        return;
    }

    const context = plot.ctx;
    const pixelRatio = uPlot.pxRatio;
    const top = plot.bbox.top;
    const bottom = plot.bbox.top + plot.bbox.height;

    context.save();
    context.font = scaledFont(font, pixelRatio);
    // lines of an even width are sharp on whole pixels, lines of an odd width in the middle of a pixel
    const lineWidth = Math.round(CHART_STYLE.markerWidth * pixelRatio);
    const pixelOffset = lineWidth % 2 === 0 ? 0 : 0.5;
    const positions = releases.map(release => Math.round(plot.valToPos(release.timestamp, "x", true)) + pixelOffset);
    const labelCenters = layoutLabels(
        releases.map((release, index) => ({x: positions[index], width: context.measureText(release.label).width})),
        context.canvas.width,
        6 * pixelRatio,
    );

    context.lineWidth = lineWidth;
    context.fillStyle = CHART_STYLE.axisColor;
    context.textBaseline = "bottom";
    context.textAlign = "center";

    releases.forEach((release, index) => {
        const style = CHART_STYLE.markers[release.kind] ?? CHART_STYLE.markers.release;
        context.strokeStyle = style.color;
        context.setLineDash(style.dash.map(length => length * pixelRatio));
        context.beginPath();
        context.moveTo(positions[index], top);
        context.lineTo(positions[index], bottom);
        context.stroke();
        if (labelCenters[index] !== null) {
            context.fillText(release.label, labelCenters[index], top - 3 * pixelRatio);
        }
    });
    context.restore();
}

/**
 * Draws the points of all series with a ring in the surface color. Incomplete values are drawn in a different color.
 * Points at the edges of the plot are not cut off.
 */
function drawPoints(plot, chartSeries) {
    const context = plot.ctx;
    const pixelRatio = uPlot.pxRatio;
    const radius = CHART_STYLE.pointRadius * pixelRatio;
    const ring = CHART_STYLE.pointRing * pixelRatio;
    const margin = radius + ring;
    const timestamps = plot.data[0];

    context.save();
    context.beginPath();
    context.rect(plot.bbox.left - margin, plot.bbox.top - margin, plot.bbox.width + 2 * margin, plot.bbox.height + 2 * margin);
    context.clip();

    chartSeries.forEach((series, index) => {
        const plotSeries = plot.series[index + 1];
        if (!plotSeries.show) {
            return;
        }
        const isDense = series.points.size > CHART_STYLE.maxPointsShown;
        const values = plot.data[index + 1];
        timestamps.forEach((timestamp, i) => {
            const isIncomplete = series.incomplete.has(timestamp);
            if (values[i] === null || values[i] === undefined || (isDense && !isIncomplete)) {
                return;
            }
            const x = plot.valToPos(timestamp, "x", true);
            const y = plot.valToPos(values[i], plotSeries.scale, true);

            if (!isDense) {
                context.beginPath();
                context.arc(x, y, radius + ring, 0, 2 * Math.PI);
                context.fillStyle = CHART_STYLE.surfaceColor;
                context.fill();
            }

            context.beginPath();
            context.arc(x, y, isDense ? CHART_STYLE.densePointRadius * pixelRatio : radius, 0, 2 * Math.PI);
            context.fillStyle = isIncomplete ? CHART_STYLE.incompleteColor : series.color;
            context.fill();
        });
    });
    context.restore();
}

/**
 * Draws a bar from the baseline to the value, with rounded corners at the value and square corners at the baseline.
 */
function drawBar(context, left, width, valueY, baseY, radius) {
    const top = Math.min(valueY, baseY);
    const height = Math.abs(baseY - valueY);
    const r = Math.min(radius, width / 2, height);
    context.beginPath();
    if (valueY <= baseY) {
        context.moveTo(left, baseY);
        context.lineTo(left, top + r);
        context.arcTo(left, top, left + r, top, r);
        context.lineTo(left + width - r, top);
        context.arcTo(left + width, top, left + width, top + r, r);
        context.lineTo(left + width, baseY);
    } else {
        // negative values grow downwards, the rounded end is at the bottom
        context.moveTo(left, baseY);
        context.lineTo(left, valueY - r);
        context.arcTo(left, valueY, left + r, valueY, r);
        context.lineTo(left + width - r, valueY);
        context.arcTo(left + width, valueY, left + width, valueY - r, r);
        context.lineTo(left + width, baseY);
    }
    context.closePath();
    context.fill();
}

/**
 * Draws the values of all series as groups of bars. Incomplete values are drawn in a different color.
 */
function drawBars(plot, chartSeries, interval) {
    const context = plot.ctx;
    const pixelRatio = uPlot.pxRatio;
    const timestamps = plot.data[0];
    const visible = chartSeries.map((series, index) => ({series, index})).filter(({index}) => plot.series[index + 1].show);
    if (visible.length === 0 || timestamps.length === 0) {
        return;
    }

    const slotWidth = Math.abs(plot.valToPos(timestamps[0] + interval, "x", true) - plot.valToPos(timestamps[0], "x", true));
    const {width, offsets} = barLayout(visible.length, slotWidth, {
        maxBarWidth: CHART_STYLE.maxBarWidth * pixelRatio,
        gap: CHART_STYLE.barGap * pixelRatio,
    });

    context.save();
    context.beginPath();
    context.rect(plot.bbox.left, plot.bbox.top, plot.bbox.width, plot.bbox.height);
    context.clip();
    visible.forEach(({series, index}, position) => {
        const scale = plot.series[index + 1].scale;
        const baseY = plot.valToPos(0, scale, true);
        plot.data[index + 1].forEach((value, i) => {
            if (value === null || value === undefined) {
                return;
            }
            const x = plot.valToPos(timestamps[i], "x", true);
            context.fillStyle = series.incomplete.has(timestamps[i]) ? CHART_STYLE.incompleteColor : series.color;
            drawBar(context, x + offsets[position], width, plot.valToPos(value, scale, true), baseY, CHART_STYLE.barRadius * pixelRatio);
        });
    });
    context.restore();
}

/**
 * Shows a tooltip with the date and the values of all series at the cursor. Only the chart, which is hovered or
 * touched, shows the tooltip, the cursors of the other charts move without a tooltip.
 */
function enableTooltip(plot, chartSeries, describe) {
    const tooltip = document.createElement("div");
    tooltip.className = "chart-tooltip chart-line-tooltip";
    tooltip.hidden = true;
    plot.over.append(tooltip);

    let isActive = false;
    plot.over.addEventListener("pointerenter", () => isActive = true);
    plot.over.addEventListener("pointerleave", event => {
        if (event.pointerType !== "touch") {
            isActive = false;
            tooltip.hidden = true;
        }
    });

    return () => {
        const index = plot.cursor.idx;
        if (!isActive || index === null || index === undefined) {
            tooltip.hidden = true;
            return;
        }

        const {date, rows} = describe(plot, index);
        if (rows.length === 0) {
            tooltip.hidden = true;
            return;
        }
        const title = document.createElement("strong");
        title.textContent = date;
        tooltip.replaceChildren(title, ...rows.map(row => {
            const line = document.createElement("div");
            line.className = "chart-tooltip-row";
            const key = document.createElement("span");
            key.className = "chart-tooltip-key";
            key.style.backgroundColor = row.color;
            const value = document.createElement("strong");
            value.textContent = row.value;
            const label = document.createElement("span");
            label.textContent = row.label;
            line.append(key, value, label);
            return line;
        }));
        tooltip.hidden = false;

        // next to the cursor, on the other side near the right edge
        const offset = 12;
        const left = plot.cursor.left + offset + tooltip.offsetWidth > plot.over.clientWidth
            ? plot.cursor.left - offset - tooltip.offsetWidth
            : plot.cursor.left + offset;
        tooltip.style.left = Math.max(0, left) + "px";
        tooltip.style.top = Math.max(0, Math.min(plot.cursor.top + offset, plot.over.clientHeight - tooltip.offsetHeight)) + "px";
    };
}

/**
 * Compares the values at the cursor with the values one month and one year before and after it: vertical lines mark
 * the dates and a table below the chart shows the values and their changes. Without a cursor on the chart, the table
 * shows the latest values.
 */
function enableComparisons(plot, {chartSeries, timestamps, values, datePeriod, formatter}) {
    // the values of each series without gaps, and the tolerance to find a value near a date
    const seriesPoints = chartSeries.map((series, index) => {
        const points = timestamps
            .map((timestamp, i) => ({timestamp, value: values[index][i]}))
            .filter(point => point.value !== null && point.value !== undefined);
        return {points, tolerance: smallestInterval(points.map(point => point.timestamp), SECONDS_PER_DAY) / 2};
    });
    const shortestInterval = Math.min(...seriesPoints.map(({tolerance}) => tolerance * 2));
    const comparisons = availableComparisons(shortestInterval);

    const lines = comparisons.map(comparison => {
        const line = document.createElement("div");
        // the labels are outside of the lines, away from the cursor, labels of months below the labels of years
        line.className = "chart-compare-line "
            + (comparison.years + comparison.months < 0 ? "chart-compare-before" : "chart-compare-after")
            + (comparison.months !== 0 ? " chart-compare-month" : "");
        line.hidden = true;
        const label = document.createElement("span");
        label.textContent = comparison.label;
        line.append(label);
        plot.over.append(line);
        return {comparison, line};
    });

    const table = document.createElement("table");
    table.className = "chart-comparison";
    plot.root.after(table);

    const latestIndex = timestamps.length - 1;
    let shownIndex = null;
    let shownSeries = null;

    const update = () => {
        const isActive = plot.cursor.left !== undefined && plot.cursor.left >= 0 && plot.cursor.idx !== null && plot.cursor.idx !== undefined;
        const index = isActive ? plot.cursor.idx : latestIndex;
        const timestamp = timestamps[index];

        for (const {comparison, line} of lines) {
            const position = isActive ? plot.valToPos(shiftDate(timestamp, comparison.years, comparison.months), "x") : -1;
            line.hidden = position < 0 || position > plot.over.clientWidth;
            if (!line.hidden) {
                line.style.left = position + "px";
            }
        }

        const visibleSeries = chartSeries.map((series, i) => plot.series[i + 1].show).join();
        if (index === shownIndex && visibleSeries === shownSeries) {
            return;
        }
        shownIndex = index;
        shownSeries = visibleSeries;
        renderComparisonTable(table, {plot, chartSeries, seriesPoints, comparisons, timestamp, datePeriod, formatter});
    };
    update();
    return update;
}

function cell(tagName, className, text) {
    const result = document.createElement(tagName);
    if (className) {
        result.className = className;
    }
    if (text !== undefined) {
        result.textContent = text;
    }
    return result;
}

/**
 * The columns are in chronological order, the values at the cursor in the middle. Each other value shows the change
 * between its date and the date of the cursor, e.g. the change since the previous year.
 */
function renderComparisonTable(table, {plot, chartSeries, seriesPoints, comparisons, timestamp, datePeriod, formatter}) {
    if (timestamp === undefined) {
        table.replaceChildren();
        return;
    }
    const columns = [
        ...comparisons.filter(c => c.years + c.months < 0),
        {label: "Cursor", years: 0, months: 0, isCursor: true},
        ...comparisons.filter(c => c.years + c.months > 0),
    ].map(column => ({...column, timestamp: shiftDate(timestamp, column.years, column.months)}));

    const headerRow = document.createElement("tr");
    headerRow.append(cell("th"));
    for (const column of columns) {
        const header = cell("th", column.isCursor ? "chart-comparison-cursor" : null);
        header.append(cell("span", "chart-comparison-label", column.label), cell("span", "chart-comparison-date", formatDate(column.timestamp, datePeriod)));
        headerRow.append(header);
    }
    const head = document.createElement("thead");
    head.append(headerRow);

    const body = document.createElement("tbody");
    chartSeries.forEach((series, index) => {
        if (!plot.series[index + 1].show) {
            return;
        }
        const {points, tolerance} = seriesPoints[index];
        const found = columns.map(column => valueNear(points, column.timestamp, tolerance));
        const current = found[columns.findIndex(column => column.isCursor)];

        const row = document.createElement("tr");
        const label = cell("th", "chart-comparison-series");
        const key = cell("span", "chart-tooltip-key");
        key.style.backgroundColor = series.color;
        label.append(key, series.isRightAxis ? series.label + " (right axis)" : series.label);
        row.append(label);

        columns.forEach((column, i) => {
            const point = found[i];
            const isIncomplete = point !== null && series.incomplete.has(point.timestamp);
            const valueCell = cell("td", column.isCursor ? "chart-comparison-cursor" : null);
            valueCell.append(cell("span", "chart-comparison-value" + (isIncomplete ? " incomplete" : ""), formatter.fixedValue(point?.value, series.unit)));
            if (isIncomplete) {
                valueCell.title = "Incomplete value, which may still change";
            }
            // every cell has the line of the change, so that the height of the table doesn't change with the cursor
            const changeText = cell("span", "chart-comparison-change", "\u00a0");
            const isEarlier = column.years + column.months < 0;
            const change = column.isCursor ? null : isEarlier
                ? valueChange(point?.value, current?.value, series.unit)
                : valueChange(current?.value, point?.value, series.unit);
            if (change !== null) {
                // an increase of a percentage may be bad, e.g. of the churn rate, it isn't colored
                const changeClass = change.unit === "pp" ? "" : change.value > 0 ? "positive" : change.value < 0 ? "negative" : "";
                changeText.className = "chart-comparison-change " + changeClass;
                changeText.textContent = formatter.change(change);
                const [from, to] = isEarlier ? [point, current] : [current, point];
                changeText.title = `Change from ${formatDate(from.timestamp, datePeriod)} to ${formatDate(to.timestamp, datePeriod)}`;
            }
            valueCell.append(changeText);
            row.append(valueCell);
        });
        body.append(row);
    });
    table.replaceChildren(head, body);
}

/**
 * uPlot moves the cursor only with a mouse. On touch devices, the cursor follows the finger.
 * Vertical swipes still scroll the dialog.
 */
function enableTouchCursor(plot) {
    const over = plot.over;
    over.style.touchAction = "pan-y";
    const moveCursor = event => {
        const touch = event.touches[0];
        if (touch !== undefined) {
            const bounds = over.getBoundingClientRect();
            plot.setCursor({left: touch.clientX - bounds.left, top: touch.clientY - bounds.top});
        }
    };
    over.addEventListener("touchstart", moveCursor, {passive: true});
    over.addEventListener("touchmove", moveCursor, {passive: true});
}

const DAY = 24 * 60 * 60;
const YEAR = 365 * DAY;

/**
 * The ticks of the date axis match the period of the values: days for daily values, years for yearly values.
 * Monthly values use the default ticks of uPlot.
 */
function xAxisTicks(period, formatter) {
    switch (period) {
        case "day":
            return {incrs: [DAY, 2 * DAY, 7 * DAY, 14 * DAY], values: (plot, splits) => splits.map(value => formatter.day(value))};
        case "year":
            return {incrs: [YEAR, 2 * YEAR, 5 * YEAR], values: (plot, splits) => splits.map(value => new Date(value * 1000).getUTCFullYear())};
        default:
            return {};
    }
}

/**
 * @return {[number, number]} The range from the start of the first year to the end of the last year, but at most until today.
 */
function yearRange([min, max]) {
    const firstYear = new Date(min * 1000).getUTCFullYear();
    const lastYear = new Date(max * 1000).getUTCFullYear();
    const end = Math.min(Date.UTC(lastYear + 1, 0, 1), Date.now()) / 1000;
    return [Date.UTC(firstYear, 0, 1) / 1000, Math.max(end, max)];
}

function yAxis(unit, scale, side, style) {
    return {
        scale,
        side,
        label: unitTitle(unit),
        labelFont: style.fonts.axisTitle,
        font: style.fonts.ticks,
        stroke: CHART_STYLE.axisColor,
        size: 64,
        // only the left axis has a grid, two grids would be confusing
        grid: {show: side === 3, stroke: CHART_STYLE.gridColor, width: 1},
        ticks: {stroke: CHART_STYLE.gridColor, width: 1},
        values: (plot, splits) => splits.map(value => style.formatter.compact(value)),
    };
}

/**
 * Creates a line chart with the series of one or two units. The second unit uses a y-axis on the right.
 *
 * @param {object} options
 * @param {string[]} options.units One or two units.
 * @param {object[]} options.series All series, only the series with the units are shown.
 * @param {[number, number]} options.dateRange Date range of the x-axis.
 * @param {{kind: string, timestamp: number, label: string}[]} options.markers Vertical lines, sorted by date.
 * @param {{value: Function, compact: Function}} options.formatter
 * @param {string} options.syncKey Charts with the same key have synchronized cursors.
 * @param {"lines"|"bars"} options.chartStyle
 * @param {"month"|"week"|"day"} [options.granularity] Values per week or per day are labeled with their day.
 * @param {string} options.fontFamily
 * @param {number} options.width
 * @param {number} options.height
 * @param {HTMLElement} options.container
 * @return {uPlot}
 */
export function createLineChart({units, series, dateRange, markers, formatter, syncKey, chartStyle, granularity, fontFamily, width, height, container}) {
    const style = {formatter, fonts: fonts(fontFamily)};
    const scaleKeys = ["y", "y2"];
    const chartSeries = units.flatMap((unit, unitIndex) => series
        .filter(s => s.unit === unit)
        .map(s => ({...s, scale: scaleKeys[unitIndex], isRightAxis: unitIndex === 1})));

    const {timestamps, values} = alignSeries(chartSeries);
    const period = shortestPeriod(chartSeries);
    const datePeriod = granularity === "week" || granularity === "day" ? "day" : period;

    const isBars = chartStyle === "bars";
    // bars at the first and last date need space beside the date
    const interval = smallestInterval(timestamps, 24 * 60 * 60);
    const xPadding = isBars ? interval / 2 : 0;
    // the tooltip lists the series, which are shown, series can be hidden by a click on the legend
    const describe = (plot, index) => ({
        date: formatDate(timestamps[index], datePeriod),
        rows: chartSeries
            .map((s, seriesIndex) => ({s, seriesIndex}))
            .filter(({seriesIndex}) => plot.series[seriesIndex + 1].show)
            .map(({s, seriesIndex}) => {
                const value = values[seriesIndex][index];
                const incomplete = value !== null && s.incomplete.has(timestamps[index]);
                return {color: s.color, label: s.label + (incomplete ? " (incomplete)" : ""), value: formatter.value(value, s.unit)};
            }),
    });

    // yearly values are in the middle of the years, the range covers the complete years until today
    const xRange = period === "year" ? yearRange(dateRange) : [dateRange[0] - xPadding, dateRange[1] + xPadding];
    const scales = {
        x: {time: true, range: () => xRange},
        y: {range: (plot, min, max) => uPlot.rangeNum(...zeroBasedRange(min, max), 0.1, true)},
    };
    const axes = [
        {
            label: "Date",
            labelFont: style.fonts.axisTitle,
            font: style.fonts.ticks,
            stroke: CHART_STYLE.axisColor,
            grid: {stroke: CHART_STYLE.gridColor, width: 1},
            ticks: {stroke: CHART_STYLE.gridColor, width: 1},
            ...xAxisTicks(period, formatter),
        },
        yAxis(units[0], "y", 3, style),
    ];
    if (units.length > 1) {
        scales.y2 = {range: scales.y.range};
        axes.push(yAxis(units[1], "y2", 1, style));
    }

    const options = {
        width,
        height,
        // the dates are UTC dates, the ticks of the date axis must not depend on the time zone of the browser
        tzDate: timestamp => uPlot.tzDate(new Date(timestamp * 1000), "Etc/UTC"),
        scales,
        axes,
        // the legend only shows and hides the series, the values are shown by the comparison table
        legend: {show: true, live: false},
        // space for the labels of the vertical lines above the plot, and for points and tick labels at the right edge
        padding: [markers.length > 0 ? 20 : null, units.length > 1 ? null : 28, null, null],
        cursor: {
            points: {size: 8},
            sync: {key: syncKey},
            // the vertical line of the cursor is at the closest value, the comparisons start at its date
            move: (plot, left, top) => {
                const index = left >= 0 ? plot.posToIdx(left) : null;
                return [index === null || plot.data[0][index] === undefined ? left : plot.valToPos(plot.data[0][index], "x"), top];
            },
        },
        hooks: {
            drawAxes: [plot => drawMarkers(plot, markers, style.fonts.releaseLabel)],
            draw: [plot => isBars ? drawBars(plot, chartSeries, interval) : drawPoints(plot, chartSeries)],
            // series are shown and hidden by a click on the legend
            setSeries: [() => updateComparisons?.()],
            setCursor: [plot => {
                updateComparisons?.(plot);
                updateTooltip?.(plot);
            }],
        },
        series: [
            {label: "Date"},
            ...chartSeries.map(s => ({
                label: s.isRightAxis ? s.label + " (right axis)" : s.label,
                scale: s.scale,
                stroke: s.color,
                width: 2,
                spanGaps: true,
                // points and bars are drawn by drawPoints and drawBars, to show incomplete values in a different color
                points: {show: false},
                ...(isBars ? {paths: () => null} : {}),
            })),
        ],
    };

    let updateTooltip = null;
    let updateComparisons = null;
    const plot = new uPlot(options, [timestamps, ...values], container);
    updateTooltip = enableTooltip(plot, chartSeries, describe);
    updateComparisons = enableComparisons(plot, {chartSeries, timestamps, values, datePeriod, formatter});
    enableTouchCursor(plot);
    legendData.set(plot, {chartSeries, markers, isBars});
    return plot;
}
