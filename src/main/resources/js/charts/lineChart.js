/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

/**
 * Line charts rendered with uPlot, which is loaded as a global script before this module.
 */

import {formatDate, unitTitle} from "./format.js";
import {alignSeries, barLayout, layoutLabels, shortestPeriod, smallestInterval, zeroBasedRange} from "./model.js";

export const CHART_STYLE = {
    axisColor: "#52514e",
    gridColor: "#e4e3df",
    surfaceColor: "#ffffff",
    // values, which may still change, use the color of incomplete values in the tables
    incompleteColor: "#6b6b6b",
    releaseLineColor: "#a8a7a2",
    pointRadius: 3,
    pointRing: 2,
    // bars are thin, with a gap between neighbors and a rounded end at the value
    maxBarWidth: 24,
    barGap: 2,
    barRadius: 4,
};


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
 * Draws a dashed vertical line with a centered label above the plot for each major release in the date range.
 * The lines are drawn before the series, so that they don't cover the data.
 */
function drawMajorReleases(plot, majorReleases, font) {
    const releases = majorReleases.filter(release => release.timestamp >= plot.scales.x.min && release.timestamp <= plot.scales.x.max);
    if (releases.length === 0) {
        return;
    }

    const context = plot.ctx;
    const pixelRatio = uPlot.pxRatio;
    const top = plot.bbox.top;
    const bottom = plot.bbox.top + plot.bbox.height;

    context.save();
    context.font = scaledFont(font, pixelRatio);
    const positions = releases.map(release => Math.round(plot.valToPos(release.timestamp, "x", true)) + 0.5);
    const labelCenters = layoutLabels(
        releases.map((release, index) => ({x: positions[index], width: context.measureText(release.label).width})),
        context.canvas.width,
        6 * pixelRatio,
    );

    context.strokeStyle = CHART_STYLE.releaseLineColor;
    context.lineWidth = pixelRatio;
    context.setLineDash([4 * pixelRatio, 4 * pixelRatio]);
    context.fillStyle = CHART_STYLE.axisColor;
    context.textBaseline = "bottom";
    context.textAlign = "center";

    releases.forEach((release, index) => {
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
        const values = plot.data[index + 1];
        timestamps.forEach((timestamp, i) => {
            if (values[i] === null || values[i] === undefined) {
                return;
            }
            const x = plot.valToPos(timestamp, "x", true);
            const y = plot.valToPos(values[i], plotSeries.scale, true);

            context.beginPath();
            context.arc(x, y, radius + ring, 0, 2 * Math.PI);
            context.fillStyle = CHART_STYLE.surfaceColor;
            context.fill();

            context.beginPath();
            context.arc(x, y, radius, 0, 2 * Math.PI);
            context.fillStyle = series.incomplete.has(timestamp) ? CHART_STYLE.incompleteColor : series.color;
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

function lastIndex(values) {
    for (let i = values.length - 1; i >= 0; i--) {
        if (values[i] !== null && values[i] !== undefined) {
            return i;
        }
    }
    return null;
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
 * @param {{timestamp: number, label: string}[]} options.majorReleases
 * @param {{value: Function, compact: Function}} options.formatter
 * @param {string} options.syncKey Charts with the same key have synchronized cursors.
 * @param {"lines"|"bars"} options.chartStyle
 * @param {string} options.fontFamily
 * @param {number} options.width
 * @param {number} options.height
 * @param {HTMLElement} options.container
 * @return {uPlot}
 */
export function createLineChart({units, series, dateRange, majorReleases, formatter, syncKey, chartStyle, fontFamily, width, height, container}) {
    const style = {formatter, fonts: fonts(fontFamily)};
    const scaleKeys = ["y", "y2"];
    const chartSeries = units.flatMap((unit, unitIndex) => series
        .filter(s => s.unit === unit)
        .map(s => ({...s, scale: scaleKeys[unitIndex], isRightAxis: unitIndex === 1})));

    const {timestamps, values} = alignSeries(chartSeries);
    const period = shortestPeriod(chartSeries);
    const lastIndices = values.map(lastIndex);
    // without a cursor on the chart, the legend shows the latest values
    const latestTimestamp = timestamps.length > 0 ? timestamps[timestamps.length - 1] : null;

    const isBars = chartStyle === "bars";
    // bars at the first and last date need space beside the date
    const interval = smallestInterval(timestamps, 24 * 60 * 60);
    const xPadding = isBars ? interval / 2 : 0;
    // the tooltip lists the series, which are shown, series can be hidden by a click on the legend
    const describe = (plot, index) => ({
        date: formatDate(timestamps[index], period),
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
        legend: {show: true},
        // space for the labels of the major releases above the plot and for points at the right edge
        padding: [majorReleases.length > 0 ? 20 : null, units.length > 1 ? null : 16, null, null],
        cursor: {points: {size: 8}, sync: {key: syncKey}},
        hooks: {
            drawAxes: [plot => drawMajorReleases(plot, majorReleases, style.fonts.releaseLabel)],
            draw: [plot => isBars ? drawBars(plot, chartSeries, interval) : drawPoints(plot, chartSeries)],
            setCursor: [plot => updateTooltip?.(plot)],
        },
        series: [
            {
                label: "Date",
                value: (plot, value) => formatDate(value ?? latestTimestamp, period),
            },
            ...chartSeries.map((s, index) => ({
                label: s.isRightAxis ? s.label + " (right axis)" : s.label,
                scale: s.scale,
                stroke: s.color,
                width: 2,
                spanGaps: true,
                // points and bars are drawn by drawPoints and drawBars, to show incomplete values in a different color
                points: {show: false},
                ...(isBars ? {paths: () => null} : {}),
                value: (plot, value, seriesIndex, dataIndex) => {
                    const i = dataIndex ?? lastIndices[index];
                    if (i === null) {
                        return formatter.value(null, s.unit);
                    }
                    const formatted = formatter.value(values[index][i], s.unit);
                    return values[index][i] !== null && s.incomplete.has(timestamps[i]) ? formatted + " (incomplete)" : formatted;
                },
            })),
        ],
    };

    let updateTooltip = null;
    const plot = new uPlot(options, [timestamps, ...values], container);
    updateTooltip = enableTooltip(plot, chartSeries, describe);
    enableTouchCursor(plot);
    return plot;
}
