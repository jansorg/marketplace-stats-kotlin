/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

/**
 * Images of the charts for downloads and for printing, with a title and a legend.
 * Line charts are images of the canvas of uPlot. Pie charts are SVG, which is converted to PNG on demand.
 */

import {CHART_STYLE, CHART_THEMES, lineChartLegend} from "./lineChart.js";
import {layoutLegend, pieArcs, slicePath} from "./model.js";

const SVG_NAMESPACE = "http://www.w3.org/2000/svg";
const TEXT_COLOR = "#1c1b22";
const PADDING = 16;
const TITLE_SIZE = 15;
const TITLE_HEIGHT = TITLE_SIZE + 12;
const TEXT_SIZE = 12;
const ROW_HEIGHT = 20;
const SWATCH_WIDTH = 14;
const SWATCH_GAP = 6;
const ENTRY_GAP = 20;
const PIE_SIZE = 200;
const PIE_LEGEND_GAP = 32;
// pie charts are converted to PNG with twice the resolution
const PIE_PNG_SCALE = 2;

let measureContext = null;

function measureText(text, font) {
    measureContext ??= document.createElement("canvas").getContext("2d");
    measureContext.font = font;
    return measureContext.measureText(text).width;
}

function drawSwatch(context, entry, x, centerY) {
    context.save();
    context.fillStyle = entry.color;
    context.strokeStyle = entry.color;
    switch (entry.shape) {
        case "box":
            context.beginPath();
            context.roundRect(x + 2, centerY - 5, 10, 10, 2);
            context.fill();
            break;
        case "stripes":
            context.beginPath();
            context.roundRect(x + 2.5, centerY - 4.5, 9, 9, 2);
            context.save();
            context.clip();
            context.lineWidth = 1.5;
            for (let offset = -10; offset <= 10; offset += 4) {
                context.beginPath();
                context.moveTo(x + 2 + offset, centerY + 5);
                context.lineTo(x + 12 + offset, centerY - 5);
                context.stroke();
            }
            context.restore();
            context.lineWidth = 1;
            context.beginPath();
            context.roundRect(x + 2.5, centerY - 4.5, 9, 9, 2);
            context.stroke();
            break;
        case "dotted":
            context.lineWidth = CHART_STYLE.lineWidth;
            context.lineCap = "round";
            context.setLineDash([0, 4]);
            context.beginPath();
            context.moveTo(x, centerY);
            context.lineTo(x + SWATCH_WIDTH, centerY);
            context.stroke();
            break;
        case "point":
            context.beginPath();
            context.arc(x + SWATCH_WIDTH / 2, centerY, CHART_STYLE.pointRadius + 1, 0, 2 * Math.PI);
            context.fill();
            break;
        case "marker":
            context.lineWidth = CHART_STYLE.markerWidth;
            context.setLineDash(entry.dash ?? []);
            context.beginPath();
            context.moveTo(x + SWATCH_WIDTH / 2, centerY - 7);
            context.lineTo(x + SWATCH_WIDTH / 2, centerY + 7);
            context.stroke();
            break;
        default:
            context.lineWidth = 3;
            context.lineCap = "round";
            // e.g. the dashes of the averages
            context.setLineDash(entry.dash ?? []);
            context.beginPath();
            context.moveTo(x + 1.5, centerY);
            context.lineTo(x + SWATCH_WIDTH - 1.5, centerY);
            context.stroke();
    }
    context.restore();
}

/**
 * @param {uPlot} plot A line chart.
 * @param {{title: string, fontFamily: string}} options
 * @return {HTMLCanvasElement} The chart with the title above and the legend below, in the resolution of the chart.
 */
export function lineChartImage(plot, {title, fontFamily}) {
    const source = plot.ctx.canvas;
    const ratio = source.width / plot.width;
    const textFont = `${TEXT_SIZE}px ${fontFamily}`;

    const entries = lineChartLegend(plot);
    const widths = entries.map(entry => SWATCH_WIDTH + SWATCH_GAP + measureText(entry.label, textFont));
    const positions = layoutLegend(widths, plot.width, ENTRY_GAP);
    const rows = positions.length === 0 ? 0 : positions[positions.length - 1].row + 1;
    const legendTop = PADDING + TITLE_HEIGHT + plot.height + 8;

    const width = plot.width + 2 * PADDING;
    const height = legendTop + rows * ROW_HEIGHT + PADDING;
    const canvas = document.createElement("canvas");
    canvas.width = Math.round(width * ratio);
    canvas.height = Math.round(height * ratio);
    canvas.dataset.cssWidth = String(width);
    const context = canvas.getContext("2d");
    context.scale(ratio, ratio);

    context.fillStyle = CHART_THEMES.light.surfaceColor;
    context.fillRect(0, 0, width, height);

    context.fillStyle = TEXT_COLOR;
    context.font = `700 ${TITLE_SIZE}px ${fontFamily}`;
    context.textBaseline = "top";
    context.fillText(title, PADDING, PADDING);

    context.drawImage(source, PADDING, PADDING + TITLE_HEIGHT, plot.width, plot.height);

    context.font = textFont;
    context.textBaseline = "middle";
    entries.forEach((entry, index) => {
        const x = PADDING + positions[index].x;
        const centerY = legendTop + positions[index].row * ROW_HEIGHT + ROW_HEIGHT / 2;
        drawSwatch(context, entry, x, centerY);
        context.fillStyle = TEXT_COLOR;
        context.fillText(entry.label, x + SWATCH_WIDTH + SWATCH_GAP, centerY);
    });
    return canvas;
}

function svgElement(tagName, attributes, text) {
    const result = document.createElementNS(SVG_NAMESPACE, tagName);
    for (const [name, value] of Object.entries(attributes)) {
        result.setAttribute(name, String(value));
    }
    if (text !== undefined) {
        result.textContent = text;
    }
    return result;
}

/**
 * @param {{label: string, slices: {label: string, value: number, color: string}[]}} pie
 * @param {{value: Function}} formatter
 * @param {string} fontFamily
 * @return {SVGSVGElement} A standalone SVG of the pie chart with its title and legend.
 */
export function pieChartSvg(pie, formatter, fontFamily) {
    const textFont = `${TEXT_SIZE}px ${fontFamily}`;
    const values = pie.slices.map(slice => formatter.value(slice.value, "%"));
    const labelWidth = Math.max(0, ...pie.slices.map(slice => measureText(slice.label, textFont)));
    const valueWidth = Math.max(0, ...values.map(value => measureText(value, textFont)));
    // the gap between labels and values leaves room for fallback fonts, which are wider than the measured font
    const legendWidth = SWATCH_WIDTH + SWATCH_GAP + labelWidth + 2 * ENTRY_GAP + valueWidth;
    const legendHeight = pie.slices.length * ROW_HEIGHT;
    const contentHeight = Math.max(PIE_SIZE, legendHeight);
    const contentTop = PADDING + TITLE_HEIGHT;

    const width = Math.ceil(2 * PADDING + PIE_SIZE + PIE_LEGEND_GAP + legendWidth);
    const height = Math.ceil(contentTop + contentHeight + PADDING);
    const svg = svgElement("svg", {
        xmlns: SVG_NAMESPACE,
        width,
        height,
        viewBox: `0 0 ${width} ${height}`,
        "font-family": fontFamily,
        "font-size": TEXT_SIZE,
        fill: TEXT_COLOR,
    });
    svg.append(
        svgElement("rect", {width, height, fill: CHART_THEMES.light.surfaceColor}),
        svgElement("text", {x: PADDING, y: PADDING, "dominant-baseline": "hanging", "font-size": TITLE_SIZE, "font-weight": 700}, pie.label),
    );

    const center = PIE_SIZE / 2;
    const slices = svgElement("g", {transform: `translate(${PADDING},${contentTop + (contentHeight - PIE_SIZE) / 2})`});
    pieArcs(pie.slices.map(slice => slice.value)).forEach((arc, index) => {
        const attributes = {fill: pie.slices[index].color, stroke: CHART_THEMES.light.surfaceColor, "stroke-width": 2};
        slices.append(arc.isFullCircle
            ? svgElement("circle", {cx: center, cy: center, r: center - 4, ...attributes})
            : svgElement("path", {d: slicePath(arc, center, center - 4), ...attributes}));
    });
    svg.append(slices);

    const legendLeft = PADDING + PIE_SIZE + PIE_LEGEND_GAP;
    const legendTop = contentTop + (contentHeight - legendHeight) / 2;
    pie.slices.forEach((slice, index) => {
        const centerY = legendTop + index * ROW_HEIGHT + ROW_HEIGHT / 2;
        svg.append(
            svgElement("rect", {x: legendLeft + 2, y: centerY - 5, width: 10, height: 10, rx: 2, fill: slice.color}),
            svgElement("text", {x: legendLeft + SWATCH_WIDTH + SWATCH_GAP, y: centerY, "dominant-baseline": "central"}, slice.label),
            svgElement("text", {x: legendLeft + legendWidth, y: centerY, "dominant-baseline": "central", "text-anchor": "end"}, values[index]),
        );
    });
    return svg;
}

function svgBlob(svg) {
    return new Blob([new XMLSerializer().serializeToString(svg)], {type: "image/svg+xml"});
}

/**
 * @return {Promise<HTMLCanvasElement>} The SVG drawn on a canvas.
 */
export async function svgCanvas(svg, scale = PIE_PNG_SCALE) {
    const url = URL.createObjectURL(svgBlob(svg));
    try {
        const image = new Image();
        image.src = url;
        await image.decode();
        const canvas = document.createElement("canvas");
        canvas.width = Math.round(svg.width.baseVal.value * scale);
        canvas.height = Math.round(svg.height.baseVal.value * scale);
        canvas.getContext("2d").drawImage(image, 0, 0, canvas.width, canvas.height);
        return canvas;
    } finally {
        URL.revokeObjectURL(url);
    }
}

function download(blob, fileName) {
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = fileName;
    document.body.append(link);
    link.click();
    link.remove();
    setTimeout(() => URL.revokeObjectURL(url), 0);
}

export async function downloadCanvas(canvas, fileName) {
    const blob = await new Promise(resolve => canvas.toBlob(resolve, "image/png"));
    download(blob, fileName);
}

/** @return {boolean} If the browser can copy images into the clipboard, it needs a secure context, e.g. localhost. */
export function canCopyImages() {
    return typeof ClipboardItem !== "undefined" && Boolean(navigator.clipboard?.write);
}

/**
 * Copies a PNG image into the clipboard. The image is passed as a promise, the clipboard must be written in the click,
 * which copies it, e.g. Safari doesn't allow it after the image was created.
 *
 * @param {Promise<HTMLCanvasElement>} canvas
 */
export async function copyCanvas(canvas) {
    const blob = canvas.then(image => new Promise(resolve => image.toBlob(resolve, "image/png")));
    await navigator.clipboard.write([new ClipboardItem({"image/png": blob})]);
}

export function downloadSvg(svg, fileName) {
    download(svgBlob(svg), fileName);
}

/**
 * Prints only the images. They are added to the page for printing and removed afterwards.
 *
 * @param {(HTMLCanvasElement|SVGSVGElement)[]} images
 */
export async function printImages(images) {
    const container = document.createElement("div");
    container.className = "chart-print";
    const figures = await Promise.all(images.map(async image => {
        const figure = document.createElement("figure");
        let printed = image;
        if (image instanceof HTMLCanvasElement) {
            printed = new Image();
            printed.src = image.toDataURL("image/png");
            await printed.decode();
        }
        // the size of the chart on the screen, the canvas has the resolution of the screen
        const width = image instanceof HTMLCanvasElement ? Number.parseFloat(image.dataset.cssWidth) : image.width.baseVal.value;
        printed.style.width = width + "px";
        figure.append(printed);
        return figure;
    }));
    container.append(...figures);
    document.body.append(container);
    document.body.classList.add("print-charts");
    window.addEventListener("afterprint", () => {
        container.remove();
        document.body.classList.remove("print-charts");
    }, {once: true});
    window.print();
}
