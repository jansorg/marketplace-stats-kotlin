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
    canCombineUnits, CHART_STYLES, groupCharts, lineChartKey, MARKER_KINDS, MAX_PIES, MAX_SERIES, nextColor, pieSlices, visibleMarkers,
} from "./model.js";
import {browserStorage, createSetupStorage} from "./storage.js";
import {chartColumns, readLineSeries, readMarkers, readPieRows} from "./tableData.js";
import {ChartDialog} from "./dialog.js";
import {createLineChart} from "./lineChart.js";
import {renderPie} from "./pieChart.js";

const STYLE_LABELS = {lines: "Lines", bars: "Bars"};

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
        this.storage = createSetupStorage(browserStorage());

        this.series = [];
        this.pies = [];
        this.plots = [];
        // stored entries of tables, which aren't on this page, are kept for other pages
        this.unresolvedEntries = [];
        this.combineUnits = false;
        this.maximized = false;
        // style of each line chart, the last selected style is used for new charts
        this.chartStyles = {};
        this.preferredStyle = "lines";
        this.shownStyles = {};
        this.dialog = null;
        this.menuLink = null;
    }

    start() {
        const columns = [...document.querySelectorAll("table[data-chart-title]")].flatMap(table => {
            const tableColumns = chartColumns(table).filter(column => column.type === "pie" || this.lineChartsAvailable);
            tableColumns.forEach(column => this.makeClickable(column, table));
            return tableColumns.map(column => ({...column, element: table}));
        });
        this.restore(columns);
        this.makeLogoClickable();
    }

    /**
     * A click on the logo of the plugin opens the charts, like the "Charts" link of the navigation.
     */
    makeLogoClickable() {
        const logo = document.querySelector("img.plugin-icon");
        if (logo === null) {
            return;
        }
        logo.classList.add("chart-opener");
        logo.tabIndex = 0;
        logo.setAttribute("role", "button");
        logo.title = "Show charts";
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
        header.classList.add("chartable");
        header.tabIndex = 0;
        header.setAttribute("role", "button");
        header.title = (header.title ? header.title + "\n" : "") + (type === "pie" ? "Click to show in a pie chart" : "Click to show in a chart");
        header.addEventListener("click", show);
        header.addEventListener("keydown", event => {
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
            return `At most ${MAX_SERIES} columns can be shown. Remove all to start again.`;
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
            return `At most ${MAX_PIES} pie charts can be shown. Remove all to start again.`;
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
        this.preferredStyle = setup.preferredStyle;
        this.shownMarkers = setup.shownMarkers;

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
        this.updateMenuLink();
    }

    save() {
        this.storage.save({
            combineUnits: this.combineUnits,
            maximized: this.maximized,
            preferredStyle: this.preferredStyle,
            chartStyles: this.chartStyles,
            shownMarkers: this.shownMarkers,
            entries: [
                ...this.series.map(s => ({type: "line", table: s.table, column: s.column, color: s.color})),
                ...this.pies.map(p => ({type: "pie", table: p.table, column: p.column})),
                ...this.unresolvedEntries,
            ],
        });
        this.updateMenuLink();
    }

    /**
     * A "Charts" link in the navigation opens the charts without adding a column.
     */
    updateMenuLink() {
        const menu = document.querySelector(".menu");
        if (menu === null) {
            return;
        }
        if (this.menuLink === null) {
            this.menuLink = document.createElement("a");
            this.menuLink.href = "#";
            this.menuLink.className = "chart-menu-link";
            this.menuLink.textContent = "Charts";
            this.menuLink.addEventListener("click", event => {
                event.preventDefault();
                this.openDialog(null);
            });
            menu.append(this.menuLink);
        }
        this.menuLink.hidden = this.series.length === 0 && this.pies.length === 0;
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
            }, MARKER_KINDS.filter(kind => this.markers.some(marker => marker.kind === kind)));
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
        dialog.clearPanels();
        dialog.setMaximized(this.maximized);
        dialog.setCombineUnits(this.combineUnits, canCombineUnits(this.series));
        dialog.setShownMarkers(this.shownMarkers);
        const markers = visibleMarkers(this.markers, this.shownMarkers);

        const charts = groupCharts(this.series, this.combineUnits);
        const height = dialog.chartHeight(charts.length);
        // the styles of the shown charts, a new style of one chart must not change the others
        this.shownStyles = {};
        for (const chart of charts) {
            const key = lineChartKey(chart);
            const chartStyle = this.chartStyles[key] ?? this.preferredStyle;
            this.shownStyles[key] = chartStyle;
            const panel = dialog.addPanel("chart-panel");
            panel.append(this.styleSwitch(key, chartStyle));
            this.plots.push(createLineChart({
                units: chart.units,
                series: this.series.filter(s => s.period === chart.period),
                dateRange: chart.dateRange,
                markers,
                formatter: this.formatter,
                syncKey: "marketplace-charts-" + chart.period,
                chartStyle,
                fontFamily: this.fontFamily,
                width: dialog.chartWidth,
                height,
                container: panel,
            }));
        }

        for (const pie of this.pies) {
            renderPie(pie, dialog.addPanel("chart-panel chart-pie-panel"), this.formatter);
        }

        if (charts.length === 0 && this.pies.length === 0) {
            dialog.showEmptyHint();
        }
        // measured after the browser has laid out the charts
        requestAnimationFrame(() => dialog.fitChartHeights(this.plots));
    }

    /**
     * @return {HTMLElement} Buttons to switch a line chart between lines and bars.
     */
    styleSwitch(key, currentStyle) {
        const group = document.createElement("div");
        group.className = "chart-style-switch";
        group.setAttribute("role", "group");
        group.setAttribute("aria-label", "Chart style");
        for (const style of CHART_STYLES) {
            const button = document.createElement("button");
            button.type = "button";
            button.textContent = STYLE_LABELS[style];
            button.setAttribute("aria-pressed", String(style === currentStyle));
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
