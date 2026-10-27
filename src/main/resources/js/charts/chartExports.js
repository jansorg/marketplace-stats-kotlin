/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

/**
 * The menu "Export" of a chart: printing, downloads, copies into the clipboard and the bookmark of the view.
 */

import {canCopyImages, copyCanvas, downloadCanvas, downloadSvg, lineChartImage, printImages} from "./exportImage.js";
import {createLineChart, lineChartTheme} from "./lineChart.js";
import {createMenu} from "./menu.js";

/**
 * @typedef {{transparent?: boolean}} ImageOptions `transparent` creates an image without a background, e.g. for printing.
 * @typedef {{fileName: (extension: string) => string, image: (options?: ImageOptions) => HTMLCanvasElement|Promise<HTMLCanvasElement>,
 *  svg?: (options?: ImageOptions) => SVGSVGElement, dataUrl?: () => string|null}} ChartExport The exports of a shown
 *  chart: the image, the SVG of a pie chart and the address of the values of a chart of metrics.
 */

/**
 * @param {ChartExport} chartExport The exports of the chart.
 * @param {object} options
 * @param {() => ChartExport[]} options.allExports The exports of all shown charts, for "Print all charts".
 * @param {() => Promise<void>} options.bookmark Shows the view in the address of the page and copies it.
 * @param {(text: string) => void} options.showMessage Shows a message in the dialog.
 * @return {HTMLElement} A menu to print the chart, to download it as PNG and, if available, as SVG, and to bookmark
 *  the view of the charts.
 */
export function exportMenu(chartExport, {allExports, bookmark, showMessage}) {
    const items = [
        {label: "Print", run: () => print([chartExport])},
        // shown when the menu opens, the other charts are added after this menu
        {label: "Print all charts", run: () => print(allExports()), isShown: () => allExports().length > 1},
        {label: "Download PNG", run: async () => downloadCanvas(await chartExport.image(), chartExport.fileName("png"))},
        // the same image as the download, e.g. to paste it into a message
        ...(canCopyImages() ? [{label: "Copy PNG", run: () => copyImage(chartExport, showMessage)}] : []),
        // the values of the chart for an AI agent or another program
        ...(chartExport.dataUrl ? [{label: "Copy data URL", run: () => copyDataUrl(chartExport, showMessage)}] : []),
        ...(chartExport.svg ? [{label: "Download SVG", run: async () => downloadSvg(chartExport.svg(), chartExport.fileName("svg"))}] : []),
        // the bookmark is the last item
        {label: "Bookmark this view", run: bookmark},
    ];

    const {menu, items: list} = createMenu("Export");
    for (const item of items) {
        const button = document.createElement("button");
        button.type = "button";
        button.setAttribute("role", "menuitem");
        button.textContent = item.label;
        button.addEventListener("click", () => {
            menu.open = false;
            item.run().catch(error => console.error("Export of the chart failed", error));
        });
        list.append(button);
        if (item.isShown) {
            menu.addEventListener("toggle", () => button.hidden = !item.isShown());
        }
    }
    return menu;
}

/**
 * Copies the address of the values of the shown lines of a chart as CSV, see chartExportUrl in model.js. Without
 * access to the clipboard, the message shows the address.
 */
async function copyDataUrl(chartExport, showMessage) {
    const url = chartExport.dataUrl();
    if (url === null) {
        showMessage("The chart has no shown lines.");
        return;
    }
    try {
        await navigator.clipboard.writeText(url);
        showMessage("The data URL was copied. It returns the values of the shown lines as CSV, e.g. for an AI agent, "
            + "with &format=json as JSON with the units and incomplete values.");
    } catch (error) {
        showMessage("The data URL of the values: " + url);
    }
}

/** Copies the image of a chart into the clipboard and shows if it was copied. */
async function copyImage(chartExport, showMessage) {
    try {
        await copyCanvas(Promise.resolve(chartExport.image()));
        showMessage("The image of the chart was copied.");
    } catch (error) {
        console.error("Copying the image failed", error);
        showMessage("The image couldn't be copied.");
    }
}

/**
 * Prints charts, pie charts as SVG and line charts as images. They have no background, only the charts are printed.
 *
 * @param {ChartExport[]} chartExports
 */
async function print(chartExports) {
    const options = {transparent: true};
    const images = await Promise.all(chartExports.map(chartExport => chartExport.svg?.(options) ?? chartExport.image(options)));
    await printImages(images);
}

/**
 * Exported and printed images use the light colors. A chart in the dark mode is drawn again with the light colors
 * for the image, outside the visible area of the page.
 *
 * @param {uPlot} plot A chart of {@link createLineChart}.
 * @param {object} chartOptions The options, with which the chart was created.
 * @param {{title: string, fontFamily: string, transparent?: boolean}} options The title and the font of the image,
 *  see lineChartImage.
 * @return {Promise<HTMLCanvasElement>}
 */
export async function lightLineChartImage(plot, chartOptions, options) {
    if (lineChartTheme(plot) === "light") {
        return lineChartImage(plot, options);
    }
    const container = document.createElement("div");
    container.style.cssText = "position: fixed; left: -10000px; top: 0;";
    document.body.append(container);
    const lightPlot = createLineChart({...chartOptions, theme: "light", width: plot.width, height: plot.height, container});
    try {
        // the same date range and the same hidden series as the shown chart
        lightPlot.setScale("x", {min: plot.scales.x.min, max: plot.scales.x.max});
        plot.series.forEach((series, index) => {
            if (index > 0 && lightPlot.series[index].show !== series.show) {
                lightPlot.setSeries(index, {show: series.show});
            }
        });
        // uPlot draws asynchronously
        await new Promise(resolve => setTimeout(resolve));
        return lineChartImage(lightPlot, options);
    } finally {
        lightPlot.destroy();
        container.remove();
    }
}
