/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

/**
 * Line charts rendered with uPlot, which is loaded as a global script before this module.
 */

import {formatDate, SECONDS_PER_DAY, unitTitle} from "./format.js";
import {createMenu} from "./menu.js";
import {alignSeries, availableComparisons, barLayout, cursorPosition, periodEnd, displayColor, extremeIndices, incompleteColor, pinchedRange, layoutLabels, logRange, logScaleValues, MARKER_LABELS, shortestPeriod, shiftDate, smallestInterval, valueChange, valueNear, yearEndTicks, zeroBasedRange, alignedRanges, niceStep, rangeTicks, seriesMenuSections, seriesStyle, shownDateRange} from "./model.js";

/**
 * The colors of the charts in the light and the dark mode. The surface is the background of the charts in the chart
 * dialog, the colors of the dark mode have the same contrast to it like the colors of the light mode on white.
 * Exported and printed images always use the light mode.
 */
export const CHART_THEMES = {
    light: {
        isDark: false,
        axisColor: "#52514e",
        gridColor: "#e4e3df",
        surfaceColor: "#ffffff",
        markerColors: {release: "#7d7c77", price: "#c27c0e", ide: "#6f74b8", custom: "#1f8a5b"},
    },
    dark: {
        isDark: true,
        axisColor: "#b0afb8",
        gridColor: "#303038",
        surfaceColor: "#1b1b21",
        markerColors: {release: "#9a99a2", price: "#e0a040", ide: "#9ea3ec", custom: "#5cc795"},
    },
};

/** @return {"light"|"dark"} The theme of new charts, the color scheme of the browser. */
export function preferredChartTheme() {
    return window.matchMedia?.("(prefers-color-scheme: dark)").matches ? "dark" : "light";
}

/** @return {"light"|"dark"} The theme of a chart created by {@link createLineChart}. */
export function lineChartTheme(plot) {
    return legendData.get(plot).theme.isDark ? "dark" : "light";
}

export const CHART_STYLE = {
    lineWidth: 2,
    // trends: dashed moving averages, a faint line of the previous year, the alpha is appended to the color of the series
    overlays: {
        average1: {dash: [3, 3], alpha: "cc"},
        average3: {dash: [6, 3], alpha: "cc"},
        average12: {dash: [14, 4], alpha: "cc"},
        previousYear: {dash: null, alpha: "59"},
    },
    overlayWidth: 1.5,
    // incomplete values: dotted lines, hollow points and striped bars in a lighter color of the series
    incompleteDotSpacing: 5,
    incompleteStripeSpacing: 5,
    // the opacity of the bars of partial periods, e.g. of the first year of a plugin
    partialBarAlpha: 0.4,
    // vertical lines: dash pattern of each kind, the colors are in CHART_THEMES, the labels use the color of the axes
    markerWidth: 2,
    markers: {
        release: {dash: [4, 4]},
        price: {dash: [2, 3]},
        ide: {dash: [8, 3]},
        custom: {dash: [10, 3, 2, 3]},
    },
    pointRadius: 3,
    // series with more values, e.g. per day, have no points, the lighter line marks their incomplete values
    maxPointsShown: 120,
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
 * @return {{label: string, color: string, shape: "line"|"dotted"|"box"|"stripes"|"point"|"marker", dash?: number[]}[]}
 */
export function lineChartLegend(plot) {
    const {chartSeries, markers, isBar, chartOverlays, theme} = legendData.get(plot);
    const min = plot.scales.x.min;
    const max = plot.scales.x.max;
    const shown = chartSeries.filter((series, index) => plot.series[index + 1].show);
    const entries = shown.map(series => ({
        label: series.isRightAxis ? series.label + " (right axis)" : series.label,
        color: series.color,
        shape: isBar(series) ? "box" : "line",
        ...(!isBar(series) && averageDashOf(series, chartSeries) ? {dash: averageDashOf(series, chartSeries)} : {}),
    }));
    if (shown.length > 0 && shown.some(series => [...series.incomplete].some(timestamp => timestamp >= min && timestamp <= max))) {
        entries.push({label: "Incomplete values", color: incompleteColor(shown[0].color, theme.isDark), shape: shown.every(isBar) ? "stripes" : "dotted"});
    }
    const kinds = new Set(markers.filter(marker => marker.timestamp >= min && marker.timestamp <= max).map(marker => marker.kind));
    for (const overlay of chartOverlays ?? []) {
        if (plot.series[chartSeries.length + chartOverlays.indexOf(overlay) + 1].show) {
            const dash = CHART_STYLE.overlays[overlay.kind].dash;
            entries.push({label: overlay.label, color: overlayColor(overlay), shape: "line", ...(dash ? {dash} : {})});
        }
    }
    for (const kind of kinds) {
        const style = CHART_STYLE.markers[kind] ?? CHART_STYLE.markers.release;
        const color = theme.markerColors[kind] ?? theme.markerColors.release;
        entries.push({label: MARKER_LABELS[kind] ?? kind, color, dash: style.dash, shape: "marker"});
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
        extremeLabel: "bold 14px " + fontFamily,
    };
}

function scaledFont(font, pixelRatio) {
    return font.replace(/^(\d+)px/, (match, size) => (size * pixelRatio) + "px");
}

/**
 * Draws a vertical line with a centered label above the plot for each marker in the date range, e.g. major releases.
 * The lines are drawn before the series, so that they don't cover the data.
 */
function drawMarkers(plot, markers, font, theme) {
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
    context.fillStyle = theme.axisColor;
    context.textBaseline = "bottom";
    context.textAlign = "center";

    releases.forEach((release, index) => {
        const style = CHART_STYLE.markers[release.kind] ?? CHART_STYLE.markers.release;
        context.strokeStyle = theme.markerColors[release.kind] ?? theme.markerColors.release;
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
 * Draws the lines to incomplete values over the lines of uPlot in a lighter, less saturated color of the series.
 */
function drawIncompleteLines(plot, chartSeries, skip, theme) {
    const context = plot.ctx;
    const pixelRatio = uPlot.pxRatio;
    const timestamps = plot.data[0];

    context.save();
    context.beginPath();
    context.rect(plot.bbox.left, plot.bbox.top, plot.bbox.width, plot.bbox.height);
    context.clip();
    context.lineJoin = "round";

    chartSeries.forEach((series, index) => {
        const plotSeries = plot.series[index + 1];
        if (!plotSeries.show || series.incomplete.size === 0 || skip(series)) {
            return;
        }
        const values = plot.data[index + 1];
        // the line to an incomplete value starts at the previous value
        const path = new Path2D();
        let previous = null;
        let wasIncomplete = false;
        timestamps.forEach((timestamp, i) => {
            if (values[i] === null || values[i] === undefined) {
                return;
            }
            const point = [plot.valToPos(timestamp, "x", true), plot.valToPos(values[i], plotSeries.scale, true)];
            const isIncomplete = series.incomplete.has(timestamp);
            if (isIncomplete) {
                if (!wasIncomplete) {
                    path.moveTo(...(previous ?? point));
                }
                path.lineTo(...point);
            }
            wasIncomplete = isIncomplete;
            previous = point;
        });

        // the solid line of uPlot is covered, the incomplete part is dotted: dashes of length 0 with round ends are dots
        context.setLineDash([]);
        context.lineCap = "round";
        context.lineWidth = CHART_STYLE.lineWidth * pixelRatio + 2;
        context.strokeStyle = theme.surfaceColor;
        context.stroke(path);
        context.setLineDash([0, CHART_STYLE.incompleteDotSpacing * pixelRatio]);
        context.lineCap = "round";
        context.lineWidth = CHART_STYLE.lineWidth * pixelRatio;
        context.strokeStyle = incompleteColor(series.color, theme.isDark);
        context.stroke(path);
    });
    context.restore();
}

/**
 * Draws the lines of the moving averages after the other series, so that bars, points and lines of values don't hide
 * them. uPlot doesn't draw them, see the paths of the series. The lines connect the values like the lines of uPlot,
 * gaps are skipped, the incomplete values are dotted by drawIncompleteLines.
 */
function drawAverageLines(plot, chartSeries, dashOf) {
    const context = plot.ctx;
    const pixelRatio = uPlot.pxRatio;
    const timestamps = plot.data[0];
    context.save();
    context.beginPath();
    context.rect(plot.bbox.left, plot.bbox.top, plot.bbox.width, plot.bbox.height);
    context.clip();
    context.lineJoin = "round";
    chartSeries.forEach((series, index) => {
        const plotSeries = plot.series[index + 1];
        if (!series.average || !plotSeries.show) {
            return;
        }
        const values = plot.data[index + 1];
        const path = new Path2D();
        let hasPoint = false;
        timestamps.forEach((timestamp, i) => {
            if (values[i] === null || values[i] === undefined) {
                return;
            }
            const x = plot.valToPos(timestamp, "x", true);
            const y = plot.valToPos(values[i], plotSeries.scale, true);
            hasPoint ? path.lineTo(x, y) : path.moveTo(x, y);
            hasPoint = true;
        });
        // like uPlot, which doesn't scale the dashes with the pixel ratio
        context.setLineDash(dashOf(series) ?? []);
        context.lineCap = "butt";
        context.lineWidth = CHART_STYLE.lineWidth * pixelRatio;
        context.strokeStyle = series.color;
        context.stroke(path);
    });
    context.restore();
}

/**
 * Draws the points of all series with a ring in the surface color. Incomplete values are drawn in a different color.
 * Points at the edges of the plot are not cut off.
 */
function drawPoints(plot, chartSeries, isBar, theme) {
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
        if (!plotSeries.show || isBar(series)) {
            return;
        }
        // the lighter line marks the incomplete values of series with many values, e.g. per day,
        // a moving average is a smooth line without points
        if (series.points.size > CHART_STYLE.maxPointsShown || series.average) {
            return;
        }
        const values = plot.data[index + 1];
        timestamps.forEach((timestamp, i) => {
            const isIncomplete = series.incomplete.has(timestamp);
            // the value of a partial period, e.g. the first year of a plugin, is a hollow point in the color of the series
            const isPartial = !isIncomplete && Boolean(series.partial?.has(timestamp));
            if (values[i] === null || values[i] === undefined) {
                return;
            }
            const x = plot.valToPos(timestamp, "x", true);
            const y = plot.valToPos(values[i], plotSeries.scale, true);

            context.beginPath();
            context.arc(x, y, radius + ring, 0, 2 * Math.PI);
            context.fillStyle = theme.surfaceColor;
            context.fill();

            context.beginPath();
            if (isIncomplete || isPartial) {
                context.arc(x, y, radius - pixelRatio, 0, 2 * Math.PI);
                context.lineWidth = 2 * pixelRatio;
                context.strokeStyle = isIncomplete ? incompleteColor(series.color, theme.isDark) : series.color;
                context.stroke();
            } else {
                context.arc(x, y, radius, 0, 2 * Math.PI);
                context.fillStyle = series.color;
                context.fill();
            }
        });
    });
    context.restore();
}

const stripePatterns = new Map();

/**
 * @return {CanvasPattern} Diagonal stripes in the color on the surface color, for incomplete bars.
 */
function stripePattern(context, color, pixelRatio, surfaceColor) {
    const key = color + "/" + surfaceColor + "/" + pixelRatio;
    if (!stripePatterns.has(key)) {
        const size = Math.round(CHART_STYLE.incompleteStripeSpacing * 2 * pixelRatio);
        const tile = document.createElement("canvas");
        tile.width = size;
        tile.height = size;
        const tileContext = tile.getContext("2d");
        tileContext.fillStyle = surfaceColor;
        tileContext.fillRect(0, 0, size, size);
        tileContext.strokeStyle = color;
        tileContext.lineWidth = 2 * pixelRatio;
        // three lines, so that the stripes continue across the edges of the tiles
        for (const offset of [-size, 0, size]) {
            tileContext.beginPath();
            tileContext.moveTo(offset, size);
            tileContext.lineTo(offset + size, 0);
            tileContext.stroke();
        }
        stripePatterns.set(key, context.createPattern(tile, "repeat"));
    }
    return stripePatterns.get(key);
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
 * @return {{visible: {series: object, index: number}[], width: number, offsets: number[]}} The shown series, the width
 *  of a bar and the offset of the left edge of the bar of each shown series to the date, in pixels of the canvas.
 */
function barGeometry(plot, chartSeries, isBar, interval) {
    const timestamps = plot.data[0];
    const visible = chartSeries.map((series, index) => ({series, index})).filter(({series, index}) => plot.series[index + 1].show && isBar(series));
    if (visible.length === 0 || timestamps.length === 0) {
        return {visible, width: 0, offsets: []};
    }
    const slotWidth = Math.abs(plot.valToPos(timestamps[0] + interval, "x", true) - plot.valToPos(timestamps[0], "x", true));
    const {width, offsets} = barLayout(visible.length, slotWidth, {
        maxBarWidth: CHART_STYLE.maxBarWidth * uPlot.pxRatio,
        gap: CHART_STYLE.barGap * uPlot.pxRatio,
    });
    return {visible, width, offsets};
}

/** @return {string} The key of an overlay in `hiddenSeries`, e.g. `metric:sales:previousYear`. */
function overlayKey(overlay) {
    return overlay.base.key + ":" + overlay.kind;
}

/** @return {string} The color of an overlay: the color of its series with the alpha of the kind of the overlay. */
function overlayColor(overlay) {
    return overlay.base.color + CHART_STYLE.overlays[overlay.kind].alpha;
}

/**
 * Marks the minimum and/or the maximum of each shown series with a ring and a label with the value.
 * The label of the maximum is above the value, the label of the minimum below it.
 */
function drawExtremes(plot, chartSeries, {kinds, isBar, interval, formatter, font, theme}) {
    const context = plot.ctx;
    const pixelRatio = uPlot.pxRatio;
    const timestamps = plot.data[0];
    const {visible, width, offsets} = barGeometry(plot, chartSeries, isBar, interval);

    context.save();
    context.font = scaledFont(font, pixelRatio);
    context.textAlign = "center";
    context.lineWidth = 2 * pixelRatio;
    // labels, which would overlap a label of another series, e.g. at the same minimum, are skipped
    const placedLabels = [];

    // partial values, e.g. of the first year of a plugin, aren't the minimum or the maximum
    const lines = chartSeries.map((series, index) => ({plotIndex: index + 1, color: series.color, unit: series.unit, fractionDigits: series.fractionDigits,
        incomplete: new Set([...series.incomplete, ...(series.partial ?? [])]), isBar: isBar(series), barIndex: index}));

    lines.forEach(series => {
        const plotSeries = plot.series[series.plotIndex];
        if (!plotSeries.show) {
            return;
        }
        const values = plot.data[series.plotIndex];
        // the extremes of the shown dates, e.g. of a zoomed chart
        const extremes = extremeIndices(timestamps, values, series.incomplete, [plot.scales.x.min, plot.scales.x.max]);
        if (extremes === null) {
            return;
        }
        const barPosition = series.isBar ? visible.findIndex(entry => entry.index === series.barIndex) : -1;

        for (const kind of ["max", "min"].filter(kind => kinds.includes(kind))) {
            const i = extremes[kind];
            const x = plot.valToPos(timestamps[i], "x", true) + (series.isBar ? offsets[barPosition] + width / 2 : 0);
            const y = plot.valToPos(values[i], plotSeries.scale, true);
            // only the value, the position at the highest or lowest point shows the kind
            const labelLines = [formatter.value(values[i], series.unit, series.fractionDigits)];

            if (!series.isBar) {
                context.beginPath();
                context.arc(x, y, (CHART_STYLE.pointRadius + CHART_STYLE.pointRing + 3) * pixelRatio, 0, 2 * Math.PI);
                context.strokeStyle = series.color;
                context.stroke();
            }

            // the label is inside of the chart, the label of a negative maximum or a positive minimum is beside the bar
            const halfWidth = Math.max(...labelLines.map(line => context.measureText(line).width)) / 2;
            const labelX = Math.min(Math.max(x, plot.bbox.left + halfWidth), plot.bbox.left + plot.bbox.width - halfWidth);
            const distance = 10 * pixelRatio;
            const lineHeight = 16 * pixelRatio;
            const height = labelLines.length * lineHeight + 2 * pixelRatio;
            const isAbove = kind === "max" ? y - distance - height > plot.bbox.top : y + distance + height > plot.bbox.top + plot.bbox.height;
            context.textBaseline = "top";

            const box = {
                left: labelX - halfWidth - 3 * pixelRatio,
                top: isAbove ? y - distance - height : y + distance,
                width: 2 * halfWidth + 6 * pixelRatio,
                height,
            };
            if (placedLabels.some(other => box.left < other.left + other.width && other.left < box.left + box.width
                && box.top < other.top + other.height && other.top < box.top + box.height)) {
                continue;
            }
            placedLabels.push(box);

            // a background in the surface color keeps the label readable on lines and grid lines
            context.fillStyle = theme.surfaceColor;
            context.fillRect(box.left, box.top, box.width, box.height);
            context.fillStyle = series.color;
            labelLines.forEach((line, lineIndex) => context.fillText(line, labelX, box.top + pixelRatio + lineIndex * lineHeight));
        }
    });
    context.restore();
}

/**
 * Draws the values of the series with bars as groups of bars. Incomplete values are drawn in a different color.
 */
function drawBars(plot, chartSeries, isBar, interval, theme) {
    const context = plot.ctx;
    const pixelRatio = uPlot.pxRatio;
    const timestamps = plot.data[0];
    const {visible, width, offsets} = barGeometry(plot, chartSeries, isBar, interval);
    if (visible.length === 0 || timestamps.length === 0) {
        return;
    }

    context.save();
    context.beginPath();
    context.rect(plot.bbox.left, plot.bbox.top, plot.bbox.width, plot.bbox.height);
    context.clip();
    visible.forEach(({series, index}, position) => {
        const scale = plot.series[index + 1].scale;
        // a logarithmic axis has no 0, the bars start at its bottom
        const baseY = plot.scales[scale].distr === 3 ? plot.bbox.top + plot.bbox.height : plot.valToPos(0, scale, true);
        plot.data[index + 1].forEach((value, i) => {
            if (value === null || value === undefined) {
                return;
            }
            const x = plot.valToPos(timestamps[i], "x", true);
            const isIncomplete = series.incomplete.has(timestamps[i]);
            // a partial period, e.g. the first year of a plugin, is a translucent bar in the color of the series,
            // wide bars have an outline
            const isPartial = !isIncomplete && Boolean(series.partial?.has(timestamps[i]));
            context.fillStyle = isIncomplete ? stripePattern(context, incompleteColor(series.color, theme.isDark), pixelRatio, theme.surfaceColor) : series.color;
            context.globalAlpha = isPartial ? CHART_STYLE.partialBarAlpha : 1;
            drawBar(context, x + offsets[position], width, plot.valToPos(value, scale, true), baseY, CHART_STYLE.barRadius * pixelRatio);
            context.globalAlpha = 1;
            if (isIncomplete || (isPartial && width > 4 * pixelRatio)) {
                context.lineWidth = pixelRatio;
                context.strokeStyle = isIncomplete ? incompleteColor(series.color, theme.isDark) : series.color;
                context.stroke();
            }
        });
    });
    context.restore();
}

/**
 * Shows a tooltip with the date and the values of all series at the cursor. The charts with synchronized cursors, e.g.
 * of the same detail level, show their tooltips together, at the values of their own dates.
 */
function enableTooltip(plot, chartSeries, describe) {
    const tooltip = document.createElement("div");
    tooltip.className = "chart-tooltip chart-line-tooltip";
    tooltip.hidden = true;
    plot.over.append(tooltip);

    return () => {
        const index = plot.cursor.idx;
        // the cursor is outside of the chart, e.g. after the mouse left the synchronized charts
        if (plot.cursor.left === undefined || plot.cursor.left < 0 || index === null || index === undefined) {
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
 * @return {number} The position on the y-axis of the shown value at the index, which is closest to the position of the
 *  mouse, or the position of the mouse without a value.
 */
function closestValuePosition(plot, index, top) {
    let closest = top;
    let distance = Infinity;
    plot.series.forEach((series, seriesIndex) => {
        const value = plot.data[seriesIndex]?.[index];
        if (seriesIndex === 0 || !series.show || value === null || value === undefined) {
            return;
        }
        const position = plot.valToPos(value, series.scale);
        if (Math.abs(position - top) < distance) {
            closest = position;
            distance = Math.abs(position - top);
        }
    });
    return closest;
}

/**
 * Compares the values at the cursor with the values one month and one year before and after it: vertical lines mark
 * the dates, if they're shown, and a table below the chart shows the values and their changes. Without a cursor on
 * the chart, the table shows the latest values.
 */
function enableComparisons(plot, {rows, timestamps, datePeriod, formatter, valueTable, comparisonLines, shownRange}) {
    // the values of each row without gaps, and the tolerance to find a value near a date
    const seriesPoints = rows.map(row => {
        const points = timestamps
            .map((timestamp, i) => ({timestamp, value: row.values[i]}))
            .filter(point => point.value !== null && point.value !== undefined);
        return {points, tolerance: smallestInterval(points.map(point => point.timestamp), SECONDS_PER_DAY) / 2};
    });
    const shortestInterval = Math.min(...seriesPoints.map(({tolerance}) => tolerance * 2));
    const comparisons = availableComparisons(shortestInterval);

    const lines = (comparisonLines ? comparisons : []).map(comparison => {
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
    if (valueTable) {
        plot.root.after(table);
    }

    // without a cursor, the table shows the latest shown values, e.g. of a zoomed chart
    const shownIndices = timestamps.flatMap((timestamp, index) => timestamp >= shownRange[0] && timestamp <= shownRange[1] ? [index] : []);
    // the latest values of the lines, not of their trends, e.g. the previous year continues after today
    const lineRows = rows.filter(row => !row.isTrend);
    const hasLineValue = index => lineRows.some(row => row.values[index] !== null && row.values[index] !== undefined);
    const lineIndices = shownIndices.filter(hasLineValue);
    const latestIndex = lineIndices.length > 0 ? lineIndices[lineIndices.length - 1]
        : shownIndices.length > 0 ? shownIndices[shownIndices.length - 1] : timestamps.length - 1;
    let shownIndex = null;
    // the labels of the rows, see renderComparisonTable
    const labels = new Map();
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

        const visibleSeries = rows.map(row => plot.series[row.plotIndex].show).join();
        if (index === shownIndex && visibleSeries === shownSeries) {
            return;
        }
        shownIndex = index;
        shownSeries = visibleSeries;
        if (valueTable) {
            renderComparisonTable(table, {plot, rows, seriesPoints, comparisons, timestamp, datePeriod, formatter, labels});
        }
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

/** The outline of a gear with 8 teeth, the icon of settings: the teeth alternate between two radii. */
const GEAR_PATH = Array.from({length: 32}, (_, i) => {
    const angle = (i + 0.5) * Math.PI / 16;
    const radius = i % 4 === 1 || i % 4 === 2 ? 8.5 : 6.4;
    return (i === 0 ? "M" : "L") + (10 + radius * Math.cos(angle)).toFixed(2) + " " + (10 + radius * Math.sin(angle)).toFixed(2);
}).join(" ") + " Z";

/**
 * Of two y-axes, an axis without a shown line is hidden, e.g. after its line was hidden in the legend, an axis without
 * lines would be confusing. A chart without any shown line keeps its axes.
 */
function showUsedAxes(plot) {
    const yAxes = plot.axes.filter(axis => axis.scale !== "x");
    if (yAxes.length < 2) {
        return;
    }
    const usedScales = new Set(plot.series.slice(1).filter(series => series.show).map(series => series.scale));
    let changed = false;
    let hasGrid = false;
    for (const axis of yAxes) {
        const show = usedScales.size === 0 || usedScales.has(axis.scale);
        // the first shown axis has the grid, also if the other axis is hidden
        const showGrid = show && !hasGrid;
        hasGrid ||= showGrid;
        if (axis.show !== show || axis.grid.show !== showGrid) {
            axis.show = show;
            axis.grid.show = showGrid;
            changed = true;
        }
    }
    // the size of the chart is calculated again with the shown axes
    if (changed) {
        plot.setSize({width: plot.width, height: plot.height});
    }
}

/**
 * @param {{average?: number}} series A line of a chart.
 * @param {{average?: number}[]} chartSeries All lines of the chart.
 * @return {number[]|null} The dashes of a line of a moving average, if the chart also shows values, so that the
 *  averages are recognized, e.g. short dashes of the 1-month average. Charts of only averages have solid lines.
 */
function averageDashOf(series, chartSeries) {
    if (!series.average || !chartSeries.some(s => !s.average)) {
        return null;
    }
    return CHART_STYLE.overlays["average" + series.average]?.dash ?? null;
}

/**
 * @param {{label: string}} series A line of a chart.
 * @param {{kind: string, title: string, items: {value: string|null, label: string, isChecked: boolean}[]}[]} sections
 *  The sections of the menu, see seriesMenuSections.
 * @param {(kind: string, value: string|null) => void} onSelect Called with the kind of the section and the selected value.
 * @return {HTMLElement} A menu with a gear icon to select the window and the style of the line, e.g. in its legend.
 */
function seriesMenu(series, sections, onSelect) {
    // the legend may scroll horizontally, the menu is placed in the window, a click on the small icon opens it
    const {menu, items} = createMenu("", {fixed: true, hover: false});
    menu.classList.add("chart-series-window");
    const summary = menu.querySelector("summary");
    const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
    svg.setAttribute("viewBox", "0 0 20 20");
    svg.setAttribute("aria-hidden", "true");
    svg.classList.add("chart-icon");
    const circle = document.createElementNS("http://www.w3.org/2000/svg", "circle");
    circle.setAttribute("cx", "10");
    circle.setAttribute("cy", "10");
    circle.setAttribute("r", "2.6");
    const gear = document.createElementNS("http://www.w3.org/2000/svg", "path");
    gear.setAttribute("d", GEAR_PATH);
    svg.append(gear, circle);
    summary.append(svg);
    // the tooltip lists the selected options, e.g. "Window: Calendar month"
    summary.title = sections.map(section => section.title + ": " + section.items.find(item => item.isChecked)?.label).join("\n");
    summary.setAttribute("aria-label", "Options of " + series.label);

    for (const section of sections) {
        const heading = document.createElement("div");
        heading.className = "chart-menu-heading";
        heading.textContent = section.title;
        items.append(heading);
        for (const item of section.items) {
            const button = document.createElement("button");
            button.type = "button";
            button.textContent = item.label;
            button.setAttribute("role", "menuitemradio");
            button.setAttribute("aria-checked", String(item.isChecked));
            button.addEventListener("click", () => {
                menu.open = false;
                onSelect(section.kind, item.value);
            });
            items.append(button);
        }
    }
    // the menu doesn't inherit the tooltip of the legend, which shows and hides the line
    menu.setAttribute("title", "");
    // a click on the menu doesn't show or hide the line
    menu.addEventListener("click", event => event.stopPropagation());
    menu.addEventListener("keydown", event => event.stopPropagation());
    return menu;
}

/**
 * The columns are in chronological order, the values at the cursor in the middle. Each other value shows the change
 * between its date and the date of the cursor, e.g. the change since the previous year.
 *
 * @param {Map<number, HTMLElement>} labels The labels of the rows, which are kept, when the table is shown again, e.g.
 *  when the cursor leaves the chart, so that an open menu of the window of a line in a label stays open.
 */
function renderComparisonTable(table, {plot, rows, seriesPoints, comparisons, timestamp, datePeriod, formatter, labels}) {
    if (timestamp === undefined) {
        table.replaceChildren();
        return;
    }
    const columns = [
        ...comparisons.filter(c => c.years + c.months < 0),
        // the column of the cursor only shows its date, the space keeps the dates of the columns aligned
        {label: "\u00a0", years: 0, months: 0, isCursor: true},
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
    rows.forEach((series, rowIndex) => {
        // the series of uPlot of the row, of the series or of an average
        const plotIndex = series.plotIndex;
        const {points, tolerance} = seriesPoints[rowIndex];
        const found = columns.map(column => valueNear(points, column.timestamp, tolerance));
        const current = found[columns.findIndex(column => column.isCursor)];

        // the table replaces the legend of uPlot: a click on the label shows or hides the series, hidden series are dimmed
        const isShown = plot.series[plotIndex].show;
        const row = document.createElement("tr");
        row.classList.toggle("chart-comparison-hidden", !isShown);
        let label = labels.get(plotIndex);
        if (label === undefined) {
            label = cell("th", "chart-comparison-series");
            label.tabIndex = 0;
            label.setAttribute("role", "button");
            const toggle = () => plot.setSeries(plotIndex, {show: !plot.series[plotIndex].show});
            label.addEventListener("click", toggle);
            label.addEventListener("keydown", event => {
                if (event.key === "Enter" || event.key === " ") {
                    event.preventDefault();
                    toggle();
                }
            });
            const key = cell("span", "chart-tooltip-key");
            key.style.backgroundColor = series.color;
            // a dashed line, e.g. of a moving average, has a dashed key
            if (series.dash) {
                key.style.background = `repeating-linear-gradient(to right, ${series.color} 0 ${series.dash[0]}px, transparent ${series.dash[0]}px ${series.dash[0] + series.dash[1]}px)`;
            }
            // the key, the label, the menu of the window and the button to remove the line are centered vertically
            const content = cell("span", "chart-comparison-series-content");
            // a long label, e.g. of a discount, is shortened with an ellipsis, its tooltip is the whole label
            const seriesLabel = cell("span", "chart-series-label", series.label);
            seriesLabel.title = series.label;
            content.append(key, seriesLabel);
            if (series.seriesMenu) {
                content.append(series.seriesMenu());
            }
            if (series.removeButton) {
                content.append(series.removeButton());
            }
            label.append(content);
            labels.set(plotIndex, label);
        }
        label.setAttribute("aria-pressed", String(isShown));
        label.title = isShown ? "Click to hide the line" : "Click to show the line";
        row.append(label);

        columns.forEach((column, i) => {
            const point = found[i];
            const isIncomplete = point !== null && series.incomplete.has(point.timestamp);
            const valueCell = cell("td", column.isCursor ? "chart-comparison-cursor" : null);
            valueCell.append(cell("span", "chart-comparison-value" + (isIncomplete ? " incomplete" : ""), formatter.fixedValue(point?.value, series.unit, series.fractionDigits)));
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
 * Vertical swipes still scroll the dialog, unless it's maximized (touch-action in main.css).
 */
function enableTouchCursor(plot) {
    const over = plot.over;
    const moveCursor = event => {
        const touch = event.touches[0];
        // two fingers zoom, see enablePinchZoom
        if (touch !== undefined && event.touches.length === 1) {
            const bounds = over.getBoundingClientRect();
            plot.setCursor({left: touch.clientX - bounds.left, top: touch.clientY - bounds.top});
        }
    };
    over.addEventListener("touchstart", moveCursor, {passive: true});
    over.addEventListener("touchmove", moveCursor, {passive: true});
}

/**
 * Two fingers zoom in and out on touch devices: the dates under the fingers stay under them. The chart shows the new
 * range while the fingers move, {@link onZoom} receives it when they're lifted.
 *
 * @param {{limits: [number, number], onZoom: Function, setPreview: (range: [number, number]|null) => void}} options
 */
function enablePinchZoom(plot, {limits, onZoom, setPreview}) {
    const over = plot.over;
    let pinch = null;
    const positions = touches => {
        const bounds = over.getBoundingClientRect();
        return [touches[0].clientX - bounds.left, touches[1].clientX - bounds.left];
    };
    over.addEventListener("touchstart", event => {
        if (event.touches.length === 2) {
            pinch = {shown: [plot.scales.x.min, plot.scales.x.max], start: positions(event.touches), range: null};
        }
    }, {passive: true});
    over.addEventListener("touchmove", event => {
        if (pinch !== null && event.touches.length === 2) {
            pinch.range = pinchedRange(pinch.shown, pinch.start, positions(event.touches), over.clientWidth, limits);
            setPreview(pinch.range);
            plot.setScale("x", {min: pinch.range[0], max: pinch.range[1]});
        }
    }, {passive: true});
    const end = event => {
        if (pinch === null || event.touches.length >= 2) {
            return;
        }
        const range = pinch.range;
        pinch = null;
        if (range !== null) {
            // all dates without a selected range
            const isAll = range[1] - range[0] >= (limits[1] - limits[0]) * 0.999;
            isAll ? onZoom(null, null) : onZoom(range[0], range[1]);
        }
    };
    over.addEventListener("touchend", end, {passive: true});
    over.addEventListener("touchcancel", end, {passive: true});
}

const DAY = SECONDS_PER_DAY;
const YEAR = 365 * DAY;

/**
 * The ticks of the date axis match the period of the values: days for daily values, years for yearly values.
 * The ticks of years are at the values of the years, the last day of each year and today for the current year, so that
 * a tick and the tooltip of its value show the same year. Monthly values use the default ticks of uPlot.
 */
function xAxisTicks(period, formatter) {
    switch (period) {
        case "day":
            return {incrs: [DAY, 2 * DAY, 7 * DAY, 14 * DAY], values: (plot, splits) => splits.map(value => formatter.day(value))};
        case "year":
            return {
                splits: (plot, axisIndex, min, max) => yearEndTicks(min, max, plot.data[0][plot.data[0].length - 1]),
                values: (plot, splits) => splits.map(value => new Date(value * 1000).getUTCFullYear()),
            };
        default:
            // the default steps of uPlot, but at least a day, e.g. the days of the past 7 days of values per day.
            // uPlot steps by calendar months for multiples of 30 days and by calendar years for multiples of 365 days.
            return {incrs: [DAY, 2 * DAY, 7 * DAY, 14 * DAY, 30 * DAY, 60 * DAY, 90 * DAY, 180 * DAY, YEAR, 2 * YEAR, 5 * YEAR, 10 * YEAR]};
    }
}

/**
 * @param {boolean} startOfYear If the range starts at the start of the first year, e.g. for values per day.
 * @return {[number, number]} The range of yearly values until the end of the last year, also of the current year, whose
 *  value is located at today. The values of the years are at the end of the years, the range starts a month before
 *  the first value, so that it isn't at the y-axis, and doesn't show an empty year before it.
 */
function yearRange([min, max], startOfYear) {
    const firstYear = new Date(min * 1000).getUTCFullYear();
    const end = periodEnd(max, "yearEnd");
    const start = startOfYear ? Date.UTC(firstYear, 0, 1) / 1000 : min - YEAR / 12;
    return [start, Math.max(end, max)];
}

function yAxis(unit, scale, side, style) {
    const theme = style.theme;
    return {
        scale,
        side,
        label: unitTitle(unit),
        labelFont: style.fonts.axisTitle,
        font: style.fonts.ticks,
        stroke: theme.axisColor,
        // without values, the axis only needs the space of its title, the chart gets the rest
        size: style.hideAxisValues ? 8 : 64,
        // only the left axis has a grid, two grids would be confusing
        grid: {show: side === 3, stroke: theme.gridColor, width: 1},
        ticks: {show: !style.hideAxisValues, stroke: theme.gridColor, width: 1},
        // the values can be hidden, e.g. to share a chart of the trend without its numbers. A logarithmic axis has ticks
        // without values (null), e.g. between 100 and 1000 only some ticks have a label
        values: (plot, splits) => splits.map(value => style.hideAxisValues || value === null ? "" : style.formatter.compact(value)),
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
 * @param {("min"|"max")[]} [options.extremes] If the minimum or the maximum of each series are marked.
 * @param {boolean} [options.valueTable] If a table compares the values at the cursor instead of the legend of uPlot.
 * @param {string|null} [options.detail] The detail level of a chart of metrics, see DETAILS in model.js.
 * @param {((key: string) => void)|null} [options.onSeriesRemove] Called with the key of a line, which is removed with
 *  the × of its legend. Without it, the legend has no buttons to remove lines.
 * @param {((key: string, window: string) => void)|null} [options.onSeriesWindowChange] Called with the key of a line of
 *  a metric and a window, which is selected in the legend.
 * @param {((key: string, style: "lines"|"bars"|null) => void)|null} [options.onSeriesStyleChange] Called with the key
 *  of a line and its style, which is selected in the legend, `null` for the style of the chart. Without it, the legend
 *  has no menus of the lines.
 * @param {boolean} [options.hideAxisValues] If the y-axes have no values, e.g. to share the chart without numbers.
 * @param {boolean} [options.logScale] If the y-axes are logarithmic, e.g. to compare lines of very different values.
 *  Values of 0 and below aren't drawn, the ticks of two y-axes aren't aligned.
 * @param {boolean} [options.comparisonLines] If vertical lines mark the dates one month and one year before and after
 *  the cursor.
 * @param {{kind: string, base: string, label: string, points: Map<number, number>}[]} [options.overlays] Additional
 *  lines of the series with the key `base`, e.g. the values of the previous year.
 * @param {[number, number]|null} [options.visibleRange] The shown dates, e.g. of a zoomed chart, all dates if null.
 *  The values outside of it are still used, e.g. by the value table to compare with the previous year.
 * @param {Set<string>} [options.hiddenSeries] The keys of the series and of the overlays (`<series>:<kind>`), which
 *  were hidden by a click on the legend, e.g. before the level of the chart was changed.
 * @param {((key: string, visible: boolean) => void)|null} [options.onSeriesVisibilityChange] Called, when a series or an
 *  overlay is shown or hidden by a click on the legend.
 * @param {((from: number|null, to: number|null) => void)|null} [options.onZoom] Called with the dates of a range,
 *  which is selected with the mouse or with two fingers, null for all dates. Without it, the chart can't be zoomed.
 * @param {string} options.fontFamily
 * @param {number} options.width
 * @param {number} options.height
 * @param {HTMLElement} options.container
 * @param {"light"|"dark"} [options.theme] The colors of the chart, by default the color scheme of the browser.
 * @return {uPlot}
 */
export function createLineChart({units, series, dateRange, markers, formatter, syncKey, chartStyle, granularity, extremes = [], valueTable = true, comparisonLines = false, hideAxisValues = false, logScale = false, detail = null, onSeriesWindowChange = null, onSeriesStyleChange = null, onSeriesRemove = null, overlays = [], visibleRange = null, onZoom = null, hiddenSeries = new Set(), onSeriesVisibilityChange = null, fontFamily, width, height, container, theme: themeName = preferredChartTheme()}) {
    const theme = CHART_THEMES[themeName];
    const style = {formatter, fonts: fonts(fontFamily), theme, hideAxisValues};
    const scaleKeys = ["y", "y2"];
    const chartSeries = units.flatMap((unit, unitIndex) => series
        .filter(s => s.unit === unit)
        .map(s => ({...s, color: displayColor(s.color, theme.isDark), scale: scaleKeys[unitIndex], isRightAxis: unitIndex === 1})));

    // the overlays of series, which aren't shown in this chart, are ignored
    const chartOverlays = overlays
        .map(overlay => ({...overlay, base: chartSeries.find(s => s.key === overlay.base)}))
        .filter(overlay => overlay.base !== undefined);
    const {timestamps, values: allValues} = alignSeries([...chartSeries, ...chartOverlays]);
    const values = allValues.slice(0, chartSeries.length);
    const overlayValues = allValues.slice(chartSeries.length);
    const overlayIndex = index => chartSeries.length + index + 1;
    const period = shortestPeriod(chartSeries);
    const isFiner = granularity === "week" || granularity === "day";
    // the values at the end of each month are shown with the month, the values at the end of each year with the year
    const datePeriod = granularity === "monthEnd" ? "month" : granularity === "yearEnd" ? "year" : isFiner ? "day" : period;

    // a line has its own style, or the style of the chart
    const isBar = series => seriesStyle(series, chartStyle) === "bars";
    const isBars = chartSeries.some(isBar);
    // bars at the first and last date need space beside the date
    const interval = smallestInterval(timestamps, SECONDS_PER_DAY);
    const xPadding = isBars ? interval / 2 : 0;
    // the tooltip lists the series, which are shown, series can be hidden by a click on the legend.
    // The values of months and years are at the last day of their window, e.g. 2025-12-31 or today for the current
    // period, the tooltip shows this date at all levels, in the locale of the page.
    const describe = (plot, index) => ({
        date: formatter.date(timestamps[index]),
        rows: chartSeries
            .map((s, seriesIndex) => ({s, seriesIndex}))
            .filter(({seriesIndex}) => plot.series[seriesIndex + 1].show)
            .map(({s, seriesIndex}) => {
                const value = values[seriesIndex][index];
                const incomplete = value !== null && s.incomplete.has(timestamps[index]);
                const partial = value !== null && !incomplete && Boolean(s.partial?.has(timestamps[index]));
                return {color: s.color, label: s.label + (incomplete ? " (incomplete)" : partial ? " (partial period)" : ""), value: formatter.value(value, s.unit, s.fractionDigits)};
            })
            .concat(chartOverlays
                .map((overlay, i) => ({overlay, value: overlayValues[i][index], isShown: plot.series[overlayIndex(i)].show}))
                .filter(({value, isShown}) => isShown && value !== null && value !== undefined)
                .map(({overlay, value}) => ({color: overlayColor(overlay), label: overlay.label, value: formatter.value(value, overlay.base.unit, overlay.base.fractionDigits)}))),
    });

    // the range of yearly values covers the years until today
    // the values per year of monthly values have the ticks and the range of yearly values
    const axisPeriod = granularity === "yearEnd" ? "year" : period;
    // the axis ends with the period of the last value, e.g. the current month, whose value is located at today
    const axisRange = range => axisPeriod === "year"
        ? yearRange(range, granularity !== "month" && granularity !== "yearEnd")
        : [range[0] - xPadding, Math.max(range[1], periodEnd(range[1], granularity ?? "month")) + xPadding];
    const fullRange = axisRange(dateRange);
    /**
     * With all dates, the axis covers the dates of the shown lines: hiding a line, whose values start earlier than
     * the values of the other lines, e.g. than a 12-month average, shortens the axis.
     *
     * @param {boolean[]} shown If each series and each overlay is shown.
     */
    const shownRange = shown => {
        if (shown.every(Boolean)) {
            return fullRange;
        }
        const range = shownDateRange(timestamps, allValues, shown);
        return range === null ? fullRange : axisRange(range);
    };
    let xRange = visibleRange !== null
        ? [visibleRange[0] - xPadding, visibleRange[1] + xPadding]
        : shownRange([...chartSeries.map(s => !hiddenSeries.has(s.key)), ...chartOverlays.map(overlay => !hiddenSeries.has(overlayKey(overlay)))]);
    // the range while two fingers zoom, see enablePinchZoom
    let pinchPreview = null;
    // the ranges and the steps of the ticks of two y-axes, see alignedRange, and the preferred number of steps
    const alignedTicks = {};
    const tickCount = Math.max(2, Math.round((height - 80) / 60));
    // a logarithmic axis (distr 3) has the powers of ten around the drawn values, see logScaleValues
    const logScaleOf = () => ({distr: 3, log: 10, range: (plot, min, max) => logRange(min, max)});
    const scales = {
        x: {time: true, range: () => pinchPreview ?? xRange},
        y: logScale ? logScaleOf() : {range: (plot, min, max) => uPlot.rangeNum(...zeroBasedRange(min, max), 0.1, true)},
    };
    const axes = [
        {
            label: "Date",
            labelFont: style.fonts.axisTitle,
            font: style.fonts.ticks,
            stroke: theme.axisColor,
            grid: {stroke: theme.gridColor, width: 1},
            ticks: {stroke: theme.gridColor, width: 1},
            // the values per week or per day of a table of days span years, they use the default ticks of uPlot
            ...xAxisTicks(period === "day" && isFiner ? "month" : axisPeriod, formatter),
        },
        yAxis(units[0], "y", 3, style),
    ];
    if (units.length > 1 && logScale) {
        // logarithmic axes have their own ticks
        scales.y2 = logScaleOf();
        axes.push(yAxis(units[1], "y2", 1, style));
    } else if (units.length > 1) {
        // the ticks of both axes are at the same heights, so that the grid lines are at values of both axes
        scales.y.range = plot => alignedRange(plot, "y");
        scales.y2 = {range: plot => alignedRange(plot, "y2")};
        axes.push(yAxis(units[1], "y2", 1, style));
        for (const axis of axes.slice(1)) {
            axis.splits = (plot, axisIndex, min, max) => rangeTicks(alignedTicks[axis.scale] ?? {min, max, step: niceStep(max - min, tickCount)});
        }
    }

    // moving averages are dashed next to values
    const averageDash = series => averageDashOf(series, chartSeries);
    // the colors of the uPlot series, the first one is the date
    const seriesColors = [null, ...chartSeries.map(s => s.color), ...chartOverlays.map(overlayColor)];
    const options = {
        width,
        height,
        // the dates are UTC dates, the ticks of the date axis must not depend on the time zone of the browser
        tzDate: timestamp => uPlot.tzDate(new Date(timestamp * 1000), "Etc/UTC"),
        scales,
        axes,
        // the legend only shows and hides the series, the table of values replaces it
        legend: {show: !valueTable, live: false},
        // space for the labels of the vertical lines above the plot, and for points and tick labels at the right edge
        padding: [markers.length > 0 ? 20 : null, units.length > 1 ? null : 28, null, null],
        cursor: {
            // the point at the cursor has the color of the axes around the color of its line, so that it's visible on the line
            points: {size: 11, width: 2.5, stroke: () => theme.axisColor, fill: (plot, seriesIndex) => seriesColors[seriesIndex] ?? theme.surfaceColor},
            sync: {key: syncKey},
            // a range selected with the mouse zooms in, see onZoom
            // uPlot stops the propagation of clicks by default, a click on the chart must close the open menus of the dialog
            drag: {x: onZoom !== null, y: false, setScale: false, click: () => {}},
            // the vertical line of the cursor is at the closest value, the comparisons start at its date, and the
            // horizontal line at the closest value at the date, so that the y-axis shows the value
            move: (plot, left, top) => {
                const index = left >= 0 ? plot.posToIdx(left) : null;
                if (index === null || plot.data[0][index] === undefined) {
                    return [left, top];
                }
                const timestamps = plot.data[0];
                const margin = CHART_STYLE.pointRadius + CHART_STYLE.pointRing;
                const position = cursorPosition(index, i => plot.valToPos(timestamps[i], "x"), timestamps.length, plot.over.clientWidth, margin);
                return [position, closestValuePosition(plot, plot.posToIdx(position), top)];
            },
        },
        hooks: {
            drawAxes: [plot => drawMarkers(plot, markers, style.fonts.releaseLabel, theme)],
            draw: [plot => {
                drawBars(plot, chartSeries, isBar, interval, theme);
                drawIncompleteLines(plot, chartSeries, s => isBar(s) || Boolean(s.average), theme);
                drawPoints(plot, chartSeries, isBar, theme);
                // the moving averages are above the values, e.g. above bars
                drawAverageLines(plot, chartSeries, averageDash);
                drawIncompleteLines(plot, chartSeries, s => !s.average, theme);
                if (extremes.length > 0) {
                    // the labels of the minimum and the maximum have no values, if the axes have none
                    const extremeFormatter = hideAxisValues ? {...formatter, value: () => "", compact: () => ""} : formatter;
                    drawExtremes(plot, chartSeries, {kinds: extremes, isBar, interval, formatter: extremeFormatter, font: style.fonts.extremeLabel, theme});
                }
            }],
            // series and overlays, e.g. moving averages, are shown and hidden one by one by a click on the legend
            setSeries: [(plot, seriesIndex) => {
                if (seriesIndex !== null) {
                    onSeriesVisibilityChange?.(seriesKey(seriesIndex), plot.series[seriesIndex].show);
                }
                if (visibleRange === null) {
                    const range = shownRange(plot.series.slice(1).map(plotSeries => plotSeries.show));
                    if (range[0] !== xRange[0] || range[1] !== xRange[1]) {
                        xRange = range;
                        plot.setScale("x", {min: range[0], max: range[1]});
                    }
                }
                updateComparisons?.();
                showUsedAxes(plot);
            }],
            setSelect: [plot => {
                const {left, width} = plot.select;
                if (onZoom === null || width < 3) {
                    return;
                }
                plot.setSelect({left: 0, top: 0, width: 0, height: 0}, false);
                onZoom(plot.posToVal(left, "x"), plot.posToVal(left + width, "x"));
            }],
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
                width: CHART_STYLE.lineWidth,
                ...(averageDash(s) ? {dash: averageDash(s)} : {}),
                spanGaps: true,
                // points and bars are drawn by drawPoints and drawBars, to show incomplete values in a different color
                points: {show: false},
                // moving averages are drawn by drawAverageLines above the other values
                ...(isBar(s) || s.average ? {paths: () => null} : {}),
                show: !hiddenSeries.has(s.key),
            })),
            // overlays are lines, also in bar charts
            ...chartOverlays.map(overlay => ({
                label: overlay.label,
                scale: overlay.base.scale,
                stroke: overlayColor(overlay),
                width: CHART_STYLE.overlayWidth,
                ...(CHART_STYLE.overlays[overlay.kind].dash ? {dash: CHART_STYLE.overlays[overlay.kind].dash} : {}),
                spanGaps: true,
                points: {show: false},
                show: !hiddenSeries.has(overlayKey(overlay)),
            })),
        ],
    };

    /**
     * @return {[number, number]|null} The smallest and the largest drawn value of the scale at the shown dates, null
     *  without shown values.
     */
    function scaleExtent(plot, scale) {
        const [from, to] = pinchPreview ?? xRange;
        let min = Infinity;
        let max = -Infinity;
        plot.series.forEach((plotSeries, index) => {
            if (index === 0 || !plotSeries.show || plotSeries.scale !== scale) {
                return;
            }
            plot.data[index].forEach((value, i) => {
                if (value !== null && value !== undefined && plot.data[0][i] >= from && plot.data[0][i] <= to) {
                    min = Math.min(min, value);
                    max = Math.max(max, value);
                }
            });
        });
        return min <= max ? [min, max] : null;
    }

    /**
     * @return {[number, number]} The range of a scale of a chart with two y-axes, see alignedRanges. A hidden axis
     *  doesn't change the range of the shown axis.
     */
    function alignedRange(plot, scale) {
        const extents = ["y", "y2"].map(key => [key, scaleExtent(plot, key)]).filter(([, extent]) => extent !== null);
        const used = extents.some(([key]) => key === scale) ? extents : [[scale, [0, 1]]];
        const ranges = alignedRanges(used.map(([, extent]) => extent), tickCount);
        used.forEach(([key], index) => alignedTicks[key] = ranges[index]);
        return [alignedTicks[scale].min, alignedTicks[scale].max];
    }

    /** @return The key of the uPlot series at the index, of a series or of an overlay, see `hiddenSeries`. */
    function seriesKey(seriesIndex) {
        return seriesIndex <= chartSeries.length ? chartSeries[seriesIndex - 1].key : overlayKey(chartOverlays[seriesIndex - chartSeries.length - 1]);
    }

    /**
     * @return The rows of the value table: a row of each series, followed by its overlays, e.g. the previous year.
     *  A click on a row only shows or hides its line.
     */
    function valueTableRows() {
        return chartSeries.flatMap((s, seriesIndex) => {
            const label = s.isRightAxis ? s.label + " (right axis)" : s.label;
            const seriesRow = [{label, color: s.color, unit: s.unit, fractionDigits: s.fractionDigits, incomplete: s.incomplete, values: values[seriesIndex], plotIndex: seriesIndex + 1,
                seriesMenu: seriesMenuOf(s), removeButton: removeButtonOf(s), dash: isBar(s) ? null : averageDash(s)}];
            return seriesRow.concat(chartOverlays
                .map((overlay, overlayIndex) => ({overlay, overlayIndex}))
                .filter(({overlay}) => overlay.base === s)
                .map(({overlay, overlayIndex}) => ({
                    label: s.isRightAxis ? overlay.label + " (right axis)" : overlay.label,
                    color: overlayColor(overlay),
                    unit: s.unit,
                    fractionDigits: s.fractionDigits,
                    incomplete: new Set(),
                    values: overlayValues[overlayIndex],
                    plotIndex: chartSeries.length + overlayIndex + 1,
                    isTrend: true,
                })));
        });
    }

    /** @return {(() => HTMLElement)|null} Creates the menu of the window and the style of a line, see seriesMenu. */
    function seriesMenuOf(series) {
        const sections = seriesMenuSections(series, Boolean(detail && onSeriesWindowChange)).filter(section => section.kind !== "style" || onSeriesStyleChange);
        if (sections.length === 0) {
            return null;
        }
        return () => seriesMenu(series, sections, (kind, value) => kind === "window"
            ? onSeriesWindowChange(series.key, value)
            : onSeriesStyleChange(series.key, value));
    }

    /** @return {(() => HTMLElement)|null} Creates the button, which removes a line from the chart. */
    function removeButtonOf(series) {
        if (!onSeriesRemove) {
            return null;
        }
        return () => {
            const button = document.createElement("button");
            button.type = "button";
            button.className = "chart-series-remove";
            button.textContent = "×";
            button.title = "Remove this line";
            button.setAttribute("aria-label", "Remove " + series.label);
            // a click doesn't show or hide the line
            button.addEventListener("click", event => {
                event.stopPropagation();
                onSeriesRemove(series.key);
            });
            button.addEventListener("keydown", event => event.stopPropagation());
            return button;
        };
    }

    let updateTooltip = null;
    let updateComparisons = null;
    // the drawn values, values of 0 and below have no position on a logarithmic axis
    const drawnValues = logScale ? [...values, ...overlayValues].map(logScaleValues) : [...values, ...overlayValues];
    const plot = new uPlot(options, [timestamps, ...drawnValues], container);
    // the lines, which were hidden before, e.g. of another level, don't show their axis
    showUsedAxes(plot);
    // without the value table, the legend of uPlot has the menus of the lines
    if (!valueTable) {
        const legendLabels = [...plot.root.querySelectorAll(".u-legend .u-series .u-label")];
        // a long label is shortened with an ellipsis, its tooltip is the whole label
        legendLabels.forEach(label => label.title = label.textContent);
        chartSeries.forEach((s, index) => {
            const legendLabel = legendLabels.find(label => label.textContent === plot.series[index + 1].label);
            const elements = [seriesMenuOf(s)?.(), removeButtonOf(s)?.()].filter(Boolean);
            legendLabel?.after(...elements);
        });
    }
    updateTooltip = enableTooltip(plot, chartSeries, describe);
    updateComparisons = enableComparisons(plot, {rows: valueTableRows(), timestamps, datePeriod, formatter, valueTable, comparisonLines, shownRange: xRange});
    enableTouchCursor(plot);
    if (onZoom !== null) {
        enablePinchZoom(plot, {limits: fullRange, onZoom, setPreview: range => pinchPreview = range});
    }
    legendData.set(plot, {chartSeries, markers, isBar, chartOverlays, theme});
    return plot;
}
