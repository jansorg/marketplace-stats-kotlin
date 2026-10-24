/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

/**
 * Charts of the columns of tables. Clicking a column header adds the column to the charts, which are shown in a dialog.
 * Columns with dates are shown as line charts, share columns like "% of Sales" as pie charts.
 * The chart setup is stored in the browser and restored on all pages with the same tables.
 */

import {createFormatter} from "./format.js";
import {
    canCombineUnits, CHART_STYLES, dateRange, exportFileName, finerPoints, GRANULARITIES, groupCharts, hasFinerValues, lineChartKey, MARKER_KINDS, MAX_PIES, MAX_SERIES, nextColor, pieSlices,
    visibleMarkers, withoutIncomplete, OVERLAYS, overlayApplies, previousYear, trailingAverage, trailingAverageOfDays,
} from "./model.js";
import {browserStorage, createSetupStorage} from "./storage.js";
import {chartColumns, readLineSeries, readMarkers, readPieRows} from "./tableData.js";
import {ChartDialog} from "./dialog.js";
import {createLineChart, lineChartTheme} from "./lineChart.js";
import {renderPie} from "./pieChart.js";
import {createMenu} from "./menu.js";
import {downloadCanvas, downloadSvg, lineChartImage, pieChartSvg, printImages, svgCanvas} from "./exportImage.js";

const STYLE_LABELS = {lines: "Lines", bars: "Bars"};
const GRANULARITY_LABELS = {month: "Month", week: "Week", day: "Day"};
const GRANULARITY_HINTS = {
    month: "Week and Day show the sums of the month before each date, e.g. the sales of the last month, "
        + "and the licenses, MRR and ARR at each date. Columns without values per day are shown per month.",
    year: "Week and Day show the sums of the year before each date, e.g. the sales of the last 12 months. "
        + "Columns without values per day are shown per year.",
    day: "Month shows the days of the table. Week and Day show the whole history, the sums of the month before each "
        + "date, e.g. the sales of the last month, and the MRR and ARR at each date.",
};

/**
 * @return {HTMLElement} A new element with the content of the element, which is added to the element.
 */
function wrapContent(element) {
    const wrapper = document.createElement("span");
    wrapper.append(...element.childNodes);
    element.append(wrapper);
    return wrapper;
}

function isTextInput(element) {
    return element instanceof HTMLElement
        && (element.isContentEditable || element.matches("textarea, select, input:not([type=checkbox], [type=radio], [type=button])"));
}

class ChartController {
    /**
     * @param {boolean} lineChartsAvailable Line charts need uPlot, pie charts are shown without it.
     */
    constructor(lineChartsAvailable) {
        this.lineChartsAvailable = lineChartsAvailable;
        this.formatter = createFormatter(document.body.dataset.locale || undefined);
        this.fontFamily = getComputedStyle(document.documentElement).fontFamily || "sans-serif";
        this.markers = readMarkers(document);
        // the vertical lines are hidden by default
        this.shownMarkers = [];
        // the marked extremes of the line charts, "min" and "max"
        this.shownExtremes = [];
        // if incomplete values, e.g. of the current month, are shown in the line charts
        this.showIncomplete = true;
        // if a table with the values at the cursor replaces the legend of the line charts
        this.showValueTable = true;
        // trends of the line charts, see OVERLAYS
        this.shownOverlays = [];
        this.hasLineCharts = false;
        this.storage = createSetupStorage(browserStorage());

        this.series = [];
        this.pies = [];
        this.plots = [];
        // the exports of the shown charts, see exportButtons
        this.exports = [];
        // stored entries of tables, which aren't on this page, are kept for other pages
        this.unresolvedEntries = [];
        this.combineUnits = false;
        this.maximized = false;
        // style of each line chart, the last selected style is used for new charts
        this.chartStyles = {};
        this.preferredStyle = "lines";
        // granularity of each line chart with values per day, e.g. "day"
        this.granularities = {};
        // values per week and per day, loaded from the server when they're shown first
        this.finerValues = {};
        this.shownStyles = {};
        this.dialog = null;
    }

    start() {
        const columns = [...document.querySelectorAll("table[data-chart-title]")].flatMap(table => {
            const tableColumns = chartColumns(table).filter(column => column.type === "pie" || this.lineChartsAvailable);
            tableColumns.forEach(column => this.makeClickable(column, table));
            this.hasLineCharts ||= tableColumns.some(column => column.type === "line");
            return tableColumns.map(column => ({...column, element: table}));
        });
        this.restore(columns);
        this.makeLogoClickable();
        this.makeHintsClickable();
        if (columns.length > 0) {
            this.enableShortcut();
        }
        // the colors of the charts follow the color scheme of the browser
        window.matchMedia?.("(prefers-color-scheme: dark)").addEventListener("change", () => {
            if (this.dialog?.isOpen) {
                this.render();
            }
        });
    }

    /**
     * The key C shows and hides the charts, unless a text field has the focus.
     */
    enableShortcut() {
        document.addEventListener("keydown", event => {
            if (event.key.toLowerCase() !== "c" || event.ctrlKey || event.metaKey || event.altKey || event.repeat || isTextInput(event.target)) {
                return;
            }
            event.preventDefault();
            if (this.dialog?.isOpen) {
                this.dialog.close();
            } else {
                this.openDialog(null);
            }
        });
    }

    /**
     * A click on a hint of the shortcut, e.g. "Press C to show the charts", opens the charts.
     */
    makeHintsClickable() {
        for (const hint of document.querySelectorAll("[data-show-charts]")) {
            hint.addEventListener("click", () => this.openDialog(null));
        }
    }

    /**
     * A click on the logo of the plugin opens the charts.
     */
    makeLogoClickable() {
        const logo = document.querySelector("img.plugin-icon");
        if (logo === null) {
            return;
        }
        logo.classList.add("chart-opener");
        logo.tabIndex = 0;
        logo.setAttribute("role", "button");
        logo.title = "Show charts (C)";
        logo.setAttribute("aria-keyshortcuts", "C");
        logo.addEventListener("click", () => this.openDialog(null));
        logo.addEventListener("keydown", event => {
            if (event.key === "Enter" || event.key === " ") {
                event.preventDefault();
                this.openDialog(null);
            }
        });
    }

    makeClickable(column, table) {
        const {header, type, columnIndex} = column;
        const show = () => type === "pie" ? this.addPie(table, columnIndex) : this.addSeries(table, columnIndex);
        // a click on the header of a sortable table sorts it, only a click on the label of the header opens the chart
        const isSortable = table.classList.contains("sortable") && !header.classList.contains("no-sort");
        const target = isSortable ? wrapContent(header) : header;
        target.classList.add("chartable");
        target.tabIndex = 0;
        target.setAttribute("role", "button");
        const hint = type === "pie" ? "Click to show in a pie chart" : "Click to show in a chart";
        target.title = (header.title ? header.title + "\n" : "") + (isSortable ? "Click the label to show in a chart, click the arrow to sort" : hint);
        target.addEventListener("click", event => {
            // the sortable tables handle all clicks on the document
            if (isSortable) {
                event.stopPropagation();
            }
            show();
        });
        target.addEventListener("keydown", event => {
            if (event.key === "Enter" || event.key === " ") {
                event.preventDefault();
                show();
            }
        });
    }

    /**
     * @return {string|null} The reason why a series can't be added, or null if it can be added.
     */
    rejectSeries(series) {
        if (this.series.some(s => s.key === series.key)) {
            return series.label + " is already shown.";
        }
        if (series.points.size === 0) {
            return series.label + " has no values to show.";
        }
        if (this.series.length >= MAX_SERIES) {
            return `At most ${MAX_SERIES} columns can be shown. Clear the charts to start again.`;
        }
        return null;
    }

    rejectPie(pie) {
        if (this.pies.some(p => p.key === pie.key)) {
            return pie.label + " is already shown.";
        }
        if (pie.slices.length === 0) {
            return pie.label + " has no values to show.";
        }
        if (this.pies.length >= MAX_PIES) {
            return `At most ${MAX_PIES} pie charts can be shown. Clear the charts to start again.`;
        }
        return null;
    }

    readPie(table, columnIndex) {
        const {rows, ...pie} = readPieRows(table, columnIndex);
        return {...pie, slices: pieSlices(rows)};
    }

    addSeries(table, columnIndex) {
        const series = readLineSeries(table, columnIndex);
        const rejection = this.rejectSeries(series);
        if (rejection === null) {
            this.series.push({...series, color: nextColor(this.series.map(s => s.color))});
            this.save();
        }
        this.openDialog(rejection);
    }

    addPie(table, columnIndex) {
        const pie = this.readPie(table, columnIndex);
        const rejection = this.rejectPie(pie);
        if (rejection === null) {
            this.pies.push(pie);
            this.save();
        }
        this.openDialog(rejection);
    }

    removeAll() {
        this.series = [];
        this.pies = [];
        this.unresolvedEntries = [];
        this.save();
        this.dialog.close();
        this.render();
    }

    /**
     * Restores the stored chart setup with the values of the tables of this page.
     */
    restore(columns) {
        const setup = this.storage.load();
        this.combineUnits = setup.combineUnits;
        this.maximized = setup.maximized;
        this.chartStyles = setup.chartStyles;
        this.granularities = setup.granularities;
        this.preferredStyle = setup.preferredStyle;
        this.shownMarkers = setup.shownMarkers;
        this.shownExtremes = setup.shownExtremes;
        this.shownOverlays = setup.shownOverlays;
        this.showIncomplete = setup.showIncomplete;
        this.showValueTable = setup.showValueTable;

        for (const entry of setup.entries) {
            const column = columns.find(c => c.type === entry.type && c.table === entry.table && c.column === entry.column);
            if (column === undefined) {
                this.unresolvedEntries.push(entry);
            } else if (entry.type === "pie") {
                const pie = this.readPie(column.element, column.columnIndex);
                if (this.rejectPie(pie) === null) {
                    this.pies.push(pie);
                }
            } else {
                const series = readLineSeries(column.element, column.columnIndex);
                if (this.rejectSeries(series) === null) {
                    // a series keeps its color across pages and sessions
                    this.series.push({...series, color: nextColor(this.series.map(s => s.color), entry.color)});
                }
            }
        }
    }

    save() {
        this.storage.save({
            combineUnits: this.combineUnits,
            maximized: this.maximized,
            preferredStyle: this.preferredStyle,
            chartStyles: this.chartStyles,
            granularities: this.granularities,
            shownMarkers: this.shownMarkers,
            shownExtremes: this.shownExtremes,
            shownOverlays: this.shownOverlays,
            showIncomplete: this.showIncomplete,
            showValueTable: this.showValueTable,
            entries: [
                ...this.series.map(s => ({type: "line", table: s.table, column: s.column, color: s.color})),
                ...this.pies.map(p => ({type: "pie", table: p.table, column: p.column})),
                ...this.unresolvedEntries,
            ],
        });
    }

    ensureDialog() {
        if (this.dialog === null) {
            this.dialog = new ChartDialog({
                onRemoveAll: () => this.removeAll(),
                onCombineUnitsChange: combine => {
                    this.combineUnits = combine;
                    this.save();
                    this.render();
                },
                onMaximizedChange: maximized => {
                    this.maximized = maximized;
                    this.save();
                    this.render();
                },
                onResize: () => this.resize(),
                onMarkerVisibilityChange: (kind, visible) => {
                    this.shownMarkers = visible ? [...new Set([...this.shownMarkers, kind])] : this.shownMarkers.filter(k => k !== kind);
                    this.save();
                    this.render();
                },
                onExtremeVisibilityChange: (kind, visible) => {
                    this.shownExtremes = visible ? [...new Set([...this.shownExtremes, kind])] : this.shownExtremes.filter(k => k !== kind);
                    this.save();
                    this.render();
                },
                onOverlayVisibilityChange: (kind, visible) => {
                    this.shownOverlays = visible ? [...new Set([...this.shownOverlays, kind])] : this.shownOverlays.filter(k => k !== kind);
                    this.save();
                    this.render();
                    // an option without effect is explained
                    if (visible && !this.dialog.isOverlayApplicable(kind)) {
                        this.dialog.showMessage(this.dialog.overlayHint(kind));
                    }
                },
                onValueTableVisibilityChange: visible => {
                    this.showValueTable = visible;
                    this.save();
                    this.render();
                },
                onIncompleteVisibilityChange: visible => {
                    this.showIncomplete = visible;
                    this.save();
                    this.render();
                },
            }, MARKER_KINDS.filter(kind => this.markers.some(marker => marker.kind === kind)), this.hasLineCharts);
        }
        return this.dialog;
    }

    openDialog(message) {
        const dialog = this.ensureDialog();
        dialog.showMessage(message);
        dialog.open();
        this.render();
    }

    render() {
        const dialog = this.ensureDialog();
        this.plots.forEach(plot => plot.destroy());
        this.plots = [];
        this.exports = [];
        dialog.clearPanels();
        dialog.setMaximized(this.maximized);
        dialog.setCombineUnits(this.combineUnits, canCombineUnits(this.series));
        dialog.setShownMarkers(this.shownMarkers);
        dialog.setShownExtremes(this.shownExtremes);
        dialog.setShowIncomplete(this.showIncomplete);
        dialog.setShowValueTable(this.showValueTable);
        dialog.setShownOverlays(this.shownOverlays);
        const markers = visibleMarkers(this.markers, this.shownMarkers);

        const charts = groupCharts(this.series, this.combineUnits);
        const height = dialog.chartHeight(charts.length);
        // the styles of the shown charts, a new style of one chart must not change the others
        this.shownStyles = {};
        // the trends, which apply to at least one series of the shown line charts
        const applicableOverlays = new Set();
        for (const chart of charts) {
            const key = lineChartKey(chart);
            let chartSeries = this.series.filter(s => s.period === chart.period);
            const canBeFiner = hasFinerValues(chartSeries.filter(s => chart.units.includes(s.unit)));
            const granularity = canBeFiner ? this.granularities[key] ?? "month" : "month";
            const finerValues = granularity === "month" ? null : this.loadFinerValues(granularity);
            if (finerValues !== null) {
                chartSeries = chartSeries.map(s => ({...s, ...(s.daily ? finerPoints(finerValues, s.daily) : null)}));
            }
            if (!this.showIncomplete) {
                chartSeries = chartSeries.map(withoutIncomplete);
            }
            // bars of every day would be too thin
            const chartStyle = granularity === "month" ? this.chartStyles[key] ?? this.preferredStyle : "lines";
            this.shownStyles[key] = this.chartStyles[key] ?? this.preferredStyle;
            const overlays = this.trends(chartSeries.filter(s => chart.units.includes(s.unit)), granularity);
            for (const series of chartSeries.filter(s => chart.units.includes(s.unit))) {
                OVERLAYS.filter(kind => overlayApplies(kind, series, granularity)).forEach(kind => applicableOverlays.add(kind));
            }
            // charts per week or day and charts without incomplete values have their own date range
            const chartDateRange = finerValues === null && this.showIncomplete
                ? chart.dateRange
                : dateRange(chartSeries.filter(s => chart.units.includes(s.unit))) ?? chart.dateRange;
            const panel = dialog.addPanel("chart-panel");
            const toolbar = this.toolbar(panel);
            const chartOptions = {
                units: chart.units,
                series: chartSeries,
                overlays,
                dateRange: chartDateRange,
                markers,
                formatter: this.formatter,
                syncKey: "marketplace-charts-" + chart.period + "-" + granularity,
                chartStyle,
                granularity,
                extremes: this.shownExtremes,
                valueTable: this.showValueTable,
                fontFamily: this.fontFamily,
            };
            const plot = createLineChart({...chartOptions, width: dialog.chartWidth, height, container: panel});
            this.plots.push(plot);
            const title = document.title;
            const labels = chartSeries.filter(s => chart.units.includes(s.unit)).map(s => s.label);
            const chartExport = {
                fileName: extension => exportFileName([title, ...labels], extension),
                image: () => this.lightLineChartImage(plot, chartOptions, title),
            };
            this.exports.push(chartExport);
            toolbar.append(this.exportMenu(chartExport));
            if (canBeFiner) {
                toolbar.append(this.granularitySwitch(key, granularity, chart.period));
            }
            // disabled instead of hidden, so that the buttons don't move
            toolbar.append(this.styleSwitch(key, chartStyle, granularity !== "month"));
        }

        for (const pie of this.pies) {
            const panel = dialog.addPanel("chart-panel chart-pie-panel");
            const svg = () => pieChartSvg(pie, this.formatter, this.fontFamily);
            const chartExport = {
                fileName: extension => exportFileName([document.title, pie.label], extension),
                image: () => svgCanvas(svg()),
                svg,
            };
            this.exports.push(chartExport);
            this.toolbar(panel).append(this.exportMenu(chartExport));
            renderPie(pie, panel, this.formatter);
        }

        if (charts.length === 0 && this.pies.length === 0) {
            dialog.showEmptyHint();
        }
        // measured after the browser has laid out the charts
        dialog.setApplicableOverlays([...applicableOverlays]);
        requestAnimationFrame(() => dialog.fitChartHeights(this.plots));
    }

    /**
     * @param {object[]} chartSeries The series of a line chart.
     * @return {object[]} The shown trends of the series, which apply to them, as additional lines of the series.
     */
    trends(chartSeries, granularity) {
        const overlays = [];
        for (const series of chartSeries) {
            for (const kind of OVERLAYS.filter(k => this.shownOverlays.includes(k) && overlayApplies(k, series, granularity))) {
                switch (kind) {
                    case "average3":
                    case "average12": {
                        const months = kind === "average3" ? 3 : 12;
                        const points = this.movingAverage(series, months, granularity);
                        if (points !== null) {
                            overlays.push({kind, base: series.key, label: `${series.label}, ${months}-month average`, points});
                        }
                        break;
                    }
                    case "previousYear":
                        overlays.push({kind, base: series.key, label: `${series.label}, previous year`, points: previousYear(series).points});
                        break;
                }
            }
        }
        return overlays.filter(overlay => overlay.points.size > 0);
    }

    /**
     * Moving averages of values per week are calculated with the values per day, which are loaded if needed.
     *
     * @return {Map<number, number>|null} The moving average at the dates of the series, or null if the values per day
     *  are still loading.
     */
    movingAverage(series, months, granularity) {
        switch (granularity) {
            case "month":
                return trailingAverage(series, months);
            case "day":
                return trailingAverageOfDays(series, months);
            default: {
                const daily = finerPoints(this.loadFinerValues("day"), series.daily);
                return daily === null ? null : trailingAverageOfDays(daily, months, series.points.keys());
            }
        }
    }

    /**
     * @return {object|null} The values per week or per day, or null if they're still loading or couldn't be loaded.
     * The charts are shown again when the values are loaded.
     */
    loadFinerValues(granularity) {
        const values = this.finerValues[granularity];
        if (values !== undefined) {
            return values;
        }
        this.finerValues[granularity] = null;
        this.dialog.showMessage("Loading the values per " + GRANULARITY_LABELS[granularity].toLowerCase() + "…");

        const url = new URL("/chart-series", window.location.href);
        const pluginId = new URLSearchParams(window.location.search).get("pluginId");
        if (pluginId !== null) {
            url.searchParams.set("pluginId", pluginId);
        }
        url.searchParams.set("granularity", granularity);
        fetch(url)
            .then(response => {
                if (!response.ok) {
                    throw new Error("HTTP status " + response.status);
                }
                return response.json();
            })
            .then(data => {
                this.finerValues[granularity] = data;
                this.dialog.showMessage(null);
                this.render();
            })
            .catch(error => {
                console.error("Loading the values per " + granularity + " failed", error);
                // loaded again when the charts are shown the next time
                delete this.finerValues[granularity];
                this.dialog.showMessage("The values per " + GRANULARITY_LABELS[granularity].toLowerCase() + " couldn't be loaded.");
            });
        return null;
    }

    /**
     * @param {"day"|"month"|"year"} period The period of the values of the table, the first button shows them.
     *  Tables of days, e.g. the days of the current month, show them with the first button "Month".
     * @return {HTMLElement} Buttons to show a line chart per month or year, per week or per day.
     */
    granularitySwitch(key, current, period) {
        const group = document.createElement("div");
        group.className = "chart-button-group";
        group.setAttribute("role", "group");
        group.setAttribute("aria-label", "Values per");
        group.title = GRANULARITY_HINTS[period];
        for (const granularity of GRANULARITIES) {
            const button = document.createElement("button");
            button.type = "button";
            button.textContent = granularity === "month" && period === "year" ? "Year" : GRANULARITY_LABELS[granularity];
            button.setAttribute("aria-pressed", String(granularity === current));
            button.addEventListener("click", () => {
                this.granularities = {...this.granularities, [key]: granularity};
                this.save();
                this.render();
            });
            group.append(button);
        }
        return group;
    }

    /**
     * The toolbar of the first chart is shown in the row of the options of the dialog to save space,
     * the toolbars of the other charts above them.
     */
    toolbar(panel) {
        const toolbar = document.createElement("div");
        toolbar.className = "chart-toolbar";
        if (panel.previousElementSibling !== null || !this.dialog.showToolbar(toolbar)) {
            panel.append(toolbar);
        }
        return toolbar;
    }

    /**
     * @param {{fileName: (extension: string) => string, image: () => HTMLCanvasElement|Promise<HTMLCanvasElement>, svg?: () => SVGSVGElement}} chartExport
     * @return {HTMLElement} A menu to print the chart and to download it as PNG and, if available, as SVG.
     */
    exportMenu(chartExport) {
        const items = [
            {label: "Print", run: () => this.print([chartExport])},
            // shown when the menu opens, the other charts are added after this menu
            {label: "Print all charts", run: () => this.print(this.exports), isShown: () => this.exports.length > 1},
            {label: "Download PNG", run: async () => downloadCanvas(await chartExport.image(), chartExport.fileName("png"))},
        ];
        if (chartExport.svg) {
            items.push({label: "Download SVG", run: async () => downloadSvg(chartExport.svg(), chartExport.fileName("svg"))});
        }

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
     * Exported and printed images use the light colors. A chart in the dark mode is drawn again with the light colors
     * for the image, outside the visible area of the page.
     *
     * @return {Promise<HTMLCanvasElement>}
     */
    async lightLineChartImage(plot, chartOptions, title) {
        if (lineChartTheme(plot) === "light") {
            return lineChartImage(plot, {title, fontFamily: this.fontFamily});
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
            return lineChartImage(lightPlot, {title, fontFamily: this.fontFamily});
        } finally {
            lightPlot.destroy();
            container.remove();
        }
    }

    /**
     * Prints charts, pie charts as SVG and line charts as images.
     */
    async print(chartExports) {
        const images = await Promise.all(chartExports.map(chartExport => chartExport.svg?.() ?? chartExport.image()));
        await printImages(images);
    }

    /**
     * @param {boolean} disabled Values per week or per day are only shown as lines.
     * @return {HTMLElement} Buttons to switch a line chart between lines and bars.
     */
    styleSwitch(key, currentStyle, disabled) {
        const group = document.createElement("div");
        group.className = "chart-button-group chart-style-switch";
        group.setAttribute("role", "group");
        group.setAttribute("aria-label", "Chart style");
        for (const style of CHART_STYLES) {
            const button = document.createElement("button");
            button.type = "button";
            button.textContent = STYLE_LABELS[style];
            button.setAttribute("aria-pressed", String(style === currentStyle));
            button.disabled = disabled;
            if (disabled) {
                button.title = "Values per week or per day are shown as lines";
            }
            button.addEventListener("click", () => {
                // only this chart changes, the other charts keep their current style
                this.chartStyles = {...this.chartStyles, ...this.shownStyles, [key]: style};
                this.preferredStyle = style;
                this.save();
                this.render();
            });
            group.append(button);
        }
        return group;
    }

    resize() {
        const height = this.dialog.chartHeight(this.plots.length);
        this.plots.forEach(plot => plot.setSize({width: this.dialog.chartWidth, height}));
        requestAnimationFrame(() => this.dialog.fitChartHeights(this.plots));
    }
}

// line charts need uPlot, which is loaded as a deferred script before this module
const lineChartsAvailable = typeof uPlot !== "undefined";
if (!lineChartsAvailable) {
    console.error("uPlot isn't available, only pie charts are shown");
}
new ChartController(lineChartsAvailable).start();
