/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

/**
 * Charts of the columns of tables. Clicking a column header adds the column to the charts, which are shown in a dialog.
 * Columns with dates are shown as line charts, share columns like "% of Sales" as pie charts.
 * Columns with a metric, e.g. the sales, show the values of the metric, which are loaded from /chart-data with the
 * window and the detail level of their chart. The same metric of different tables is the same series. Other columns
 * show the values of their table.
 * The chart setup is stored in the browser. The series of metrics are restored on all pages, the other series and the
 * pie charts on all pages with the same tables.
 */

import {createFormatter} from "./format.js";
import {
    canCombineUnits, chartLevel, chartLevelKeys, CHART_STYLES, dateRange, DETAILS, detailGranularity, detailPeriod, rangeLabel, rangeOfPreset, rangeOfTimestamps, rangeTimestamps, rangeYears, exportFileName, groupCharts, lineChartKey, MARKER_KINDS, MAX_PIES, MAX_SERIES,
    METRIC_PERIOD, metricLabel, metricPoints, nextColor, pieSlices, shownWindow, storedChartLevel, WINDOW_LABELS, WINDOWS, finestDetail, lineDetail,
    visibleMarkers, withoutIncomplete, OVERLAYS, overlayApplies, previousYear,
} from "./model.js";
import {browserStorage, createSetupStorage, decodeView, emptySetup, encodeView} from "./storage.js";
import {chartColumns, metricKey, readLineSeries, readMarkers, readMetricLevel, readMetricSeries, readPieRows, readRowHistorySeries, readTableDateRange} from "./tableData.js";
import {ChartDialog, removeButton} from "./dialog.js";
import {createLineChart, lineChartTheme} from "./lineChart.js";
import {renderPie} from "./pieChart.js";
import {createMenu} from "./menu.js";
import {canCopyImages, copyCanvas, downloadCanvas, downloadSvg, lineChartImage, pieChartSvg, printImages, svgCanvas} from "./exportImage.js";

const STYLE_LABELS = {lines: "Lines", bars: "Bars"};
const LOADING_MESSAGE = "Loading the values…";

/** The values of a metric in the menu "Add": the values and their moving averages over 1, 3 and 12 months. */
const ADD_MENU_VALUES = [
    [null, "Values", "The values of the selected window"],
    [1, "1-month moving average", "The average of the month before each date"],
    [3, "3-month moving average", "The average of the 3 months before each date"],
    [12, "12-month moving average", "The average of the 12 months before each date, without seasonal changes"],
];
const DETAIL_LABELS = {year: "Year", month: "Month", week: "Week", day: "Day"};
const DETAIL_HINT = "The dates of the values: the end of each year, of each month or of each week, or each day, and today.";

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

/**
 * @param {{metric: string, window?: string|null, usesWindow?: boolean, label: string, unit: string,
 *  measure?: string|null, row?: string}} series A series of a metric column, of the menu to add metrics or of the
 *  stored setup. Sums and rates have a window, e.g. the trials of each month and of each day are two series.
 * @return {object} The series of the metric, its values are loaded when it's shown, see ChartController.metricChartSeries.
 */
function metricSeries({metric, window, usesWindow, average, label, unit, measure, row, style}, color) {
    const hasWindow = usesWindow ?? measure !== "stock";
    const seriesWindow = hasWindow ? window ?? null : null;
    return {
        key: metricKey(metric, seriesWindow, average), metric, window: seriesWindow, usesWindow: hasWindow,
        ...(average ? {average} : {}), label, unit, measure: measure ?? null, ...(row ? {row} : {}),
        period: METRIC_PERIOD, points: new Map(), incomplete: new Set(), color, ...(style ? {style} : {}),
    };
}

/**
 * @return {string} The ID of the values of a series of a metric in /chart-data: the metric or its moving average, e.g.
 *  `sales@avg3`.
 */
function metricDataId(series) {
    return series.average ? `${series.metric}@avg${series.average}` : series.metric;
}

/**
 * On small screens, a group of buttons, of which one is selected, e.g. the levels Year, Month, Week and Day, is a
 * dropdown menu with the selected button as its label, which opens on hover or with a tap. See main.css.
 *
 * @param {HTMLElement} group The buttons with `aria-pressed`.
 * @return {HTMLElement} The buttons and the menu, CSS shows one of them.
 */
function compactSwitch(group) {
    const buttons = [...group.querySelectorAll("button")];
    const selected = buttons.find(button => button.getAttribute("aria-pressed") === "true");
    const {menu, items} = createMenu(selected?.textContent ?? "");
    menu.classList.add("chart-compact-switch");
    menu.title = group.title;
    menu.querySelector("summary").setAttribute("aria-label", (group.getAttribute("aria-label") ?? "") + ": " + (selected?.textContent ?? ""));
    for (const button of buttons) {
        const item = document.createElement("button");
        item.type = "button";
        item.textContent = button.textContent;
        item.setAttribute("role", "menuitemradio");
        item.setAttribute("aria-checked", String(button === selected));
        item.disabled = button.disabled;
        item.title = button.title;
        item.addEventListener("click", () => {
            menu.open = false;
            button.click();
        });
        items.append(item);
    }
    const wrapper = document.createElement("div");
    wrapper.className = "chart-switch";
    group.classList.add("chart-full-switch");
    wrapper.append(group, menu);
    return wrapper;
}

/** @return {import("./storage.js").ChartEntry} The stored entry of a series of a metric. */
function metricEntry(s) {
    return {type: "metric", metric: s.metric, ...(s.window ? {window: s.window} : {}), usesWindow: s.usesWindow, ...(s.average ? {average: s.average} : {}), label: s.label, unit: s.unit,
        ...(s.measure ? {measure: s.measure} : {}), ...(s.row ? {row: s.row} : {}), color: s.color, ...(s.style ? {style: s.style} : {})};
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
        // the IDs of the shown custom events, see readMarkers
        this.shownEvents = [];
        // the marked extremes of the line charts, "min" and "max"
        this.shownExtremes = [];
        // if incomplete values, e.g. of the current month, are shown in the line charts
        this.showIncomplete = true;
        // if a table with the values at the cursor replaces the legend of the line charts
        this.showValueTable = true;
        // if vertical lines mark the dates one month and one year before and after the cursor
        this.showComparisonLines = false;
        // if the y-axes have no values, e.g. to share an image of a trend without its numbers
        this.hideAxisValues = false;
        // if lines with a moving average only show the average
        // the shown dates of the line charts, null for all dates, see storage.js
        this.dateRange = null;
        // the keys of the series and overlays, which were hidden by a click on the legend, see createLineChart
        this.hiddenSeries = new Set();
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
        this.combineUnits = true;
        this.maximized = true;
        // style of each line chart, the last selected style is used for new charts
        this.chartStyles = {};
        this.preferredStyle = "lines";
        // the window and the detail level of each line chart of metrics, see chartLevel
        this.chartLevels = {};
        // the window of metrics, which are added with the menu "Add"
        this.addWindow = "follow";
        // the loaded values of metrics by window and detail level, see loadMetrics
        this.metricValues = {};
        this.shownStyles = {};
        this.dialog = null;
    }

    start() {
        const columns = [...document.querySelectorAll("table[data-chart-title]")].flatMap(table => {
            const tableColumns = chartColumns(table).filter(column => column.type === "pie" || this.lineChartsAvailable);
            tableColumns.forEach(column => this.makeClickable(column, table));
            this.hasLineCharts ||= tableColumns.some(column => column.type !== "pie");
            return tableColumns.map(column => ({...column, element: table}));
        });
        const isBookmarkedView = this.restore(columns);
        this.makeLogoClickable();
        this.makeHintsClickable();
        if (columns.length > 0) {
            this.enableShortcut();
        }
        // a bookmarked view replaces the stored setup and is shown
        if (isBookmarkedView) {
            this.save();
            this.openDialog(null);
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
        const {header, type, columnIndex, isRowHistory} = column;
        const show = () => type === "pie" ? this.addPie(table, columnIndex)
            : type === "metric" ? this.addMetricSeries(table, columnIndex)
                : isRowHistory ? this.addRowHistorySeries(table, columnIndex) : this.addSeries(table, columnIndex);
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
     * @return {string|null} The reason why a series can't be added, or null if it can be added. An empty reason for a
     *  series, which is already shown: the dialog shows it without a message.
     */
    rejectSeries(series) {
        if (this.series.some(s => s.key === series.key)) {
            return "";
        }
        // the values of metrics are loaded when they're shown
        if (!series.metric && series.points.size === 0) {
            return series.label + " has no values to show.";
        }
        if (this.series.length >= MAX_SERIES) {
            return `At most ${MAX_SERIES} columns can be shown. Clear the charts to start again.`;
        }
        return null;
    }

    /** @see rejectSeries */
    rejectPie(pie) {
        if (this.pies.some(p => p.key === pie.key)) {
            return "";
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
            this.setTableDateRange(table);
            this.series.push({...series, color: nextColor(this.series.map(s => s.color))});
            this.save();
        }
        this.openDialog(rejection);
    }

    /**
     * Adds a series for each row of a column with the values of the rows over time, e.g. one for each customer type.
     */
    addRowHistorySeries(table, columnIndex) {
        this.setTableDateRange(table);
        let rejection = null;
        for (const series of readRowHistorySeries(table, columnIndex)) {
            rejection = this.rejectSeries(series);
            // the rows, which are already shown, are skipped
            if (rejection === "") {
                continue;
            }
            if (rejection !== null) {
                break;
            }
            this.series.push({...series, color: nextColor(this.series.map(s => s.color))});
        }
        this.save();
        this.openDialog(rejection);
    }

    /**
     * Adds the series of a metric column, a series for each row of a column with the values of the rows over time.
     * A new chart shows the values of the table, an existing chart of the unit keeps its level. A metric, which is
     * already shown, is shown again with the level and the dates of the table, e.g. the trials of the table of the
     * years after the trials of the table of the months.
     * The charts of a table of a few days, e.g. of the past 7 days, show its dates.
     */
    addMetricSeries(table, columnIndex) {
        const level = readMetricLevel(table, columnIndex);
        const allSeries = readMetricSeries(table, columnIndex);
        // the metric is shown again with the window, the level and the dates of the table, also with another window
        const shownSeries = this.series.filter(s => s.metric && allSeries.some(series => series.metric === s.metric));
        if (shownSeries.length > 0) {
            this.dateRange = readTableDateRange(table);
            shownSeries.filter(s => s.usesWindow).forEach(s => this.setSeriesWindow(s, allSeries[0].window));
            this.removeDuplicateSeries();
            if (level !== null) {
                new Set(shownSeries.map(s => s.unit)).forEach(unit => this.setTableLevel(unit, level));
            }
        } else {
            this.setTableDateRange(table);
        }
        let rejection = null;
        for (const series of allSeries) {
            rejection = this.rejectSeries(series);
            // the rows, which are already shown, are skipped
            if (rejection === "") {
                continue;
            }
            if (rejection !== null) {
                break;
            }
            if (level !== null && !this.series.some(s => s.period === METRIC_PERIOD && s.unit === series.unit)) {
                this.setTableLevel(series.unit, level);
            }
            this.series.push(metricSeries(series, nextColor(this.series.map(s => s.color))));
        }
        this.save();
        this.openDialog(rejection);
    }

    /**
     * Adds a metric of the menu "Add" to the chart of its unit, sums and rates with the window.
     *
     * @param {{id: string, title: string, unit: string, measure: string, usesWindow: boolean}} metric A metric of /chart-metrics.
     */
    addMetric(metric, window, average = null) {
        const series = metricSeries({metric: metric.id, window, usesWindow: metric.usesWindow, average, label: metric.title,
            unit: metric.unit, measure: metric.measure}, nextColor(this.series.map(s => s.color)));
        const rejection = this.rejectSeries(series);
        if (rejection === null) {
            this.series.push(series);
            this.save();
        }
        this.dialog.showMessage(rejection || null);
        this.render();
    }

    /**
     * Fills the menu to add a metric: a selection of the window of sums and rates, and the metrics of the page, which
     * are loaded from /chart-metrics when the menu is opened first.
     */
    fillAddMenu(items, close) {
        if (items.childElementCount > 0) {
            return;
        }
        const url = new URL("/chart-metrics", window.location.href);
        const pluginId = new URLSearchParams(window.location.search).get("pluginId");
        if (pluginId !== null) {
            url.searchParams.set("pluginId", pluginId);
        }
        const windowLabel = document.createElement("label");
        windowLabel.className = "chart-add-window";
        const windowSelect = document.createElement("select");
        for (const window of WINDOWS) {
            windowSelect.append(new Option(WINDOW_LABELS[window], window, false, window === this.addWindow));
        }
        windowSelect.addEventListener("change", () => this.addWindow = windowSelect.value);
        windowLabel.append("Window", windowSelect);
        items.append(windowLabel);
        fetch(url)
            .then(response => {
                if (!response.ok) {
                    throw new Error("HTTP status " + response.status);
                }
                return response.json();
            })
            .then(metrics => {
                // the metrics are grouped by their kind, e.g. the trials and the conversion of the trials
                let group = null;
                for (const metric of metrics) {
                    if (metric.group !== group) {
                        group = metric.group;
                        const heading = document.createElement("span");
                        heading.className = "chart-menu-heading";
                        heading.textContent = group;
                        items.append(heading);
                    }
                    items.append(this.addMenuItem(metric, () => windowSelect.value, close));
                }
            })
            .catch(error => {
                console.error("Loading the metrics failed", error);
                items.replaceChildren();
                close();
                this.dialog.showMessage("The metrics couldn't be loaded.");
            });
    }

    /**
     * @return {HTMLElement} An item of the menu "Add": a click adds the values of the metric, hovering it shows a
     *  submenu with the values and the moving averages.
     */
    addMenuItem(metric, selectedWindow, close) {
        const item = document.createElement("div");
        item.className = "chart-add-metric";
        const add = average => {
            close();
            this.addMetric(metric, selectedWindow(), average);
        };
        const button = document.createElement("button");
        button.type = "button";
        button.setAttribute("role", "menuitem");
        button.setAttribute("aria-haspopup", "menu");
        button.textContent = metric.title;
        // values at a date, e.g. the MRR, don't use the window
        button.title = metric.usesWindow ? "Sums or rates of the selected window" : "Values at each date";
        // a mouse shows the submenu on hover and a click adds the values, a tap of a touch screen shows the submenu
        let pointerType = "mouse";
        button.addEventListener("pointerdown", event => pointerType = event.pointerType);
        button.addEventListener("click", event => {
            if (event.detail > 0 && pointerType !== "mouse") {
                show();
            } else {
                add(null);
            }
        });

        const submenu = document.createElement("div");
        submenu.className = "chart-add-submenu";
        submenu.setAttribute("role", "menu");
        for (const [average, text, title] of ADD_MENU_VALUES) {
            const subButton = document.createElement("button");
            subButton.type = "button";
            subButton.setAttribute("role", "menuitem");
            subButton.textContent = text;
            subButton.title = title;
            subButton.addEventListener("click", () => add(average));
            submenu.append(subButton);
        }
        // the submenu is placed beside the item in the window, to the left like the menu, the list of the menu scrolls.
        // On a narrow screen without space beside the item, the submenu is placed below it.
        const show = () => {
            item.parentElement?.querySelectorAll(".chart-add-open").forEach(open => open !== item && open.classList.remove("chart-add-open"));
            item.classList.add("chart-add-open");
            const bounds = item.getBoundingClientRect();
            const {width, height} = submenu.getBoundingClientRect();
            const fitsRight = bounds.right + width <= window.innerWidth;
            const fitsLeft = bounds.left - width >= 0;
            const left = fitsLeft ? bounds.left - width : fitsRight ? bounds.right : Math.min(bounds.left, window.innerWidth - width);
            const top = fitsLeft || fitsRight ? bounds.top : bounds.bottom;
            submenu.style.left = Math.max(0, left) + "px";
            submenu.style.top = Math.max(0, Math.min(top, window.innerHeight - height)) + "px";
        };
        item.addEventListener("pointerenter", event => event.pointerType === "mouse" && show());
        item.addEventListener("focusin", show);
        item.addEventListener("pointerleave", event => event.pointerType === "mouse" && item.classList.remove("chart-add-open"));
        item.addEventListener("focusout", event => {
            if (!item.contains(event.relatedTarget)) {
                item.classList.remove("chart-add-open");
            }
        });
        item.append(button, submenu);
        return item;
    }

    /** Changes the window of a series of a metric, e.g. to show the trials of each day instead of each month. */
    setSeriesWindow(series, window) {
        series.window = window;
        series.key = metricKey(series.metric, window, series.average);
    }

    /** Removes series, which became the same series, e.g. after changing the window of the lines of a chart. */
    removeDuplicateSeries() {
        this.series = this.series.filter((s, index) => this.series.findIndex(other => other.key === s.key) === index);
    }

    /**
     * Shows the chart of the unit with the level of a table. The stored levels of combined charts with the unit don't
     * apply to it.
     */
    setTableLevel(unit, level) {
        this.chartLevels = Object.fromEntries(Object.entries(this.chartLevels)
            .filter(([key]) => !(key.startsWith(METRIC_PERIOD + "/") && key.includes("+") && key.substring(METRIC_PERIOD.length + 1).split("+").includes(unit))));
        this.setChartLevel({period: METRIC_PERIOD, units: [unit]}, level);
    }

    /**
     * The first line chart shows the dates of its table, e.g. all dates or the past 7 days, a stored range of earlier
     * charts doesn't apply to it. The charts of a table of a few days always show its dates.
     */
    setTableDateRange(table) {
        const dateRange = readTableDateRange(table);
        if (dateRange !== null || this.series.length === 0) {
            this.dateRange = dateRange;
        }
    }

    /** Sets the window and the detail level of a chart of metrics. */
    setChartLevel(chart, level) {
        this.chartLevels = {...this.chartLevels, ...Object.fromEntries(chartLevelKeys(chart).map(key => [key, level]))};
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

    /**
     * Removes all charts and resets the whole setup, e.g. the styles, the levels of the charts, the range and the
     * options of the menus, also of the charts of other pages.
     */
    removeAll() {
        this.series = [];
        this.pies = [];
        this.unresolvedEntries = [];
        this.applySetup(emptySetup());
        this.save();
        this.dialog.close();
        this.render();
    }

    /** Applies the options of a setup, e.g. the styles and the levels of the charts, without its entries. */
    applySetup(setup) {
        this.combineUnits = setup.combineUnits;
        this.maximized = setup.maximized;
        this.chartStyles = setup.chartStyles;
        this.chartLevels = setup.chartLevels;
        this.preferredStyle = setup.preferredStyle;
        this.shownMarkers = setup.shownMarkers;
        this.shownEvents = setup.shownEvents;
        this.shownExtremes = setup.shownExtremes;
        this.shownOverlays = setup.shownOverlays;
        this.showIncomplete = setup.showIncomplete;
        this.showValueTable = setup.showValueTable;
        this.showComparisonLines = setup.showComparisonLines;
        this.hideAxisValues = setup.hideAxisValues;
        // the moving averages are lines of the menu "Add", only the previous year is a trend of the lines
        this.shownOverlays = this.shownOverlays.filter(kind => kind === "previousYear");
        // a range, which ends today, e.g. the past 7 days, has the dates of today
        this.dateRange = setup.dateRange?.preset ? rangeOfPreset(setup.dateRange.preset) ?? setup.dateRange : setup.dateRange;
        this.hiddenSeries = new Set(setup.hiddenSeries);
    }

    /**
     * Restores the chart setup of a bookmarked view or the stored chart setup with the values of the tables of this page.
     *
     * @return {boolean} If the setup of a bookmarked view was restored, see {@link bookmark}.
     */
    restore(columns) {
        const view = decodeView(window.location.hash);
        const setup = view ?? this.storage.load();
        this.applySetup(setup);

        for (const entry of setup.entries) {
            if (entry.type === "metric") {
                // a series of version 2 without a window has the window of its chart
                const window = entry.window ?? storedChartLevel({period: METRIC_PERIOD, units: [entry.unit]}, this.chartLevels).window;
                const series = metricSeries({...entry, window}, nextColor(this.series.map(s => s.color), entry.color));
                // line charts need uPlot
                if (!this.lineChartsAvailable) {
                    this.unresolvedEntries.push(entry);
                } else if (this.rejectSeries(series) === null) {
                    this.series.push(series);
                }
                continue;
            }
            // a series of a table of version 1 of the setup is a metric, if the column has a metric
            const column = columns.find(c => (c.type === entry.type || (entry.type === "line" && c.type === "metric"))
                && c.table === entry.table && c.column === entry.column);
            if (column === undefined) {
                this.unresolvedEntries.push(entry);
            } else if (entry.type === "pie") {
                const pie = this.readPie(column.element, column.columnIndex);
                if (this.rejectPie(pie) === null) {
                    this.pies.push(pie);
                }
            } else if (column.type === "metric") {
                const series = readMetricSeries(column.element, column.columnIndex).find(s => s.row === entry.row);
                if (series !== undefined && this.rejectSeries(series) === null) {
                    this.series.push(metricSeries({...series, style: entry.style}, nextColor(this.series.map(s => s.color), entry.color)));
                }
            } else {
                const series = column.isRowHistory
                    ? readRowHistorySeries(column.element, column.columnIndex).find(s => s.row === entry.row)
                    : readLineSeries(column.element, column.columnIndex);
                if (series === undefined) {
                    this.unresolvedEntries.push(entry);
                    continue;
                }
                if (this.rejectSeries(series) === null) {
                    // a series keeps its color across pages and sessions
                    this.series.push({...series, color: nextColor(this.series.map(s => s.color), entry.color), ...(entry.style ? {style: entry.style} : {})});
                }
            }
        }
        return view !== null;
    }

    save() {
        this.storage.save(this.currentSetup());
        // the address of a bookmarked view doesn't match the changed view
        if (decodeView(window.location.hash) !== null) {
            history.replaceState(history.state, "", window.location.pathname + window.location.search);
        }
    }

    /**
     * Shows the current view in the address of the page, so that it can be bookmarked, and copies the address.
     * Opening the address shows the view again, see {@link restore}.
     */
    async bookmark() {
        const url = new URL(window.location.href);
        url.hash = encodeView(this.currentSetup());
        history.replaceState(history.state, "", url);
        let copied = false;
        try {
            await navigator.clipboard.writeText(url.href);
            copied = true;
        } catch (e) {
            // the address is still shown, e.g. if the page has no access to the clipboard
        }
        this.dialog?.showMessage("The address of the page shows this view, press Ctrl+D to bookmark it."
            + (copied ? " The address was copied." : ""));
    }

    /** @return {import("./storage.js").ChartSetup} The current setup of the charts. */
    currentSetup() {
        return {
            combineUnits: this.combineUnits,
            maximized: this.maximized,
            preferredStyle: this.preferredStyle,
            chartStyles: this.chartStyles,
            chartLevels: this.chartLevels,
            shownMarkers: this.shownMarkers,
            shownEvents: this.shownEvents,
            shownExtremes: this.shownExtremes,
            shownOverlays: this.shownOverlays,
            showIncomplete: this.showIncomplete,
            showValueTable: this.showValueTable,
            showComparisonLines: this.showComparisonLines,
            hideAxisValues: this.hideAxisValues,
            averagesOnly: false,
            dateRange: this.dateRange,
            hiddenSeries: [...this.hiddenSeries],
            entries: [
                ...this.series.map(s => s.metric
                    ? metricEntry(s)
                    : {type: "line", table: s.table, column: s.column, ...(s.row ? {row: s.row} : {}), color: s.color, ...(s.style ? {style: s.style} : {})}),
                ...this.pies.map(p => ({type: "pie", table: p.table, column: p.column})),
                ...this.unresolvedEntries,
            ],
        };
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
                onEventVisibilityChange: (id, visible) => {
                    this.shownEvents = visible ? [...new Set([...this.shownEvents, id])] : this.shownEvents.filter(e => e !== id);
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
                    // an option without effect is explained, the values of the averages may still load
                    if (visible && !this.isLoadingMetrics() && !this.dialog.isOverlayApplicable(kind)) {
                        this.dialog.showMessage(this.dialog.overlayHint(kind));
                    }
                },
                onDateRangeChange: range => this.setDateRange(range),
                onAddMenuOpen: (items, close) => this.fillAddMenu(items, close),
                onValueTableVisibilityChange: visible => {
                    this.showValueTable = visible;
                    this.save();
                    this.render();
                },
                onHideAxisValuesChange: hidden => {
                    this.hideAxisValues = hidden;
                    this.save();
                    this.render();
                },
                onComparisonLinesVisibilityChange: visible => {
                    this.showComparisonLines = visible;
                    this.save();
                    this.render();
                },
                onIncompleteVisibilityChange: visible => {
                    this.showIncomplete = visible;
                    this.save();
                    this.render();
                },
            }, MARKER_KINDS.filter(kind => this.markers.some(marker => marker.kind === kind)), this.hasLineCharts,
            this.markers.filter(marker => marker.kind === "custom"));
        }
        return this.dialog;
    }

    /** @param {{from: string, to: string}|null} range The shown dates of the line charts, null for all dates. */
    setDateRange(range) {
        if (rangeLabel(range) === rangeLabel(this.dateRange)) {
            return;
        }
        this.dateRange = range;
        this.save();
        // the synchronized charts report the same selection, the charts are drawn once
        if (!this.isRenderPending) {
            this.isRenderPending = true;
            requestAnimationFrame(() => {
                this.isRenderPending = false;
                this.render();
            });
        }
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
        dialog.setShownEvents(this.shownEvents);
        dialog.setShownExtremes(this.shownExtremes);
        dialog.setShowIncomplete(this.showIncomplete);
        dialog.setShowValueTable(this.showValueTable);
        dialog.setShowComparisonLines(this.showComparisonLines);
        dialog.setHideAxisValues(this.hideAxisValues);
        dialog.setShownOverlays(this.shownOverlays);
        const markers = visibleMarkers(this.markers, this.shownMarkers, this.shownEvents);

        const charts = groupCharts(this.series, this.combineUnits);
        // one request for each level with the missing values of all charts of metrics
        const requests = new Map();
        for (const chart of charts.filter(c => c.period === METRIC_PERIOD)) {
            const level = chartLevel(chart, this.chartLevels);
            for (const s of this.series.filter(s => s.period === chart.period && chart.units.includes(s.unit))) {
                const window = this.seriesWindow(s, level);
                const detail = this.seriesDetail(s, window, level);
                const key = window + "/" + detail;
                const ids = [metricDataId(s)];
                requests.set(key, {window, detail, ids: [...(requests.get(key)?.ids ?? []), ...ids]});
            }
        }
        requests.forEach(({window, detail, ids}) => this.loadMetrics(window, detail, ids));
        // with several charts, each chart can be removed
        const isRemovable = charts.length + this.pies.length > 1;
        const visibleRange = rangeTimestamps(this.dateRange);
        const height = dialog.chartHeight(charts.length);
        // the styles of the shown charts, a new style of one chart must not change the others
        this.shownStyles = {};
        // the trends, which apply to at least one series of the shown line charts
        const applicableOverlays = new Set();
        // the series with values, e.g. for the years of the menu "Range"
        const shownSeries = [];
        // values of metrics, which are still loading, e.g. after a change of the level
        let isLoading = false;
        let shownCharts = 0;
        for (const chart of charts) {
            const key = lineChartKey(chart);
            const level = chart.period === METRIC_PERIOD ? chartLevel(chart, this.chartLevels) : null;
            let chartSeries = this.series.filter(s => s.period === chart.period && chart.units.includes(s.unit));
            if (level !== null) {
                chartSeries = this.metricChartSeries(chartSeries, level);
                // the values are still loading, or the metrics have no values
                if (chartSeries.length === 0) {
                    isLoading ||= Object.values(this.metricValues).some(entry => entry.pending);
                    continue;
                }
            }
            shownSeries.push(...chartSeries);
            shownCharts++;
            if (!this.showIncomplete) {
                chartSeries = chartSeries.map(withoutIncomplete);
            }
            const detail = level?.detail ?? null;
            const chartStyle = this.chartStyles[key] ?? this.preferredStyle;
            this.shownStyles[key] = this.chartStyles[key] ?? this.preferredStyle;
            const overlays = this.trends(chartSeries, detail);
            for (const series of chartSeries) {
                OVERLAYS.filter(kind => overlayApplies(kind, series, detail, level?.window)).forEach(kind => applicableOverlays.add(kind));
            }
            // charts of metrics and charts without incomplete values have their own date range
            const chartDateRange = level === null && this.showIncomplete
                ? chart.dateRange
                : dateRange(chartSeries) ?? chart.dateRange;
            const panel = dialog.addPanel("chart-panel");
            const toolbar = this.toolbar(panel);
            const chartOptions = {
                units: chart.units,
                series: chartSeries,
                overlays,
                dateRange: chartDateRange,
                markers,
                formatter: this.formatter,
                // the cursors of the charts with the same dates are synchronized
                syncKey: "marketplace-charts-" + (detail === null ? chart.period : "detail-" + detail),
                chartStyle,
                // the values of tables are shown like monthly values
                // the dates of a chart with a line per day, e.g. of the sales per day in a chart of months, are days
                granularity: detail === null ? "month" : detailGranularity(finestDetail([detail, ...chartSeries.map(s => s.detail)])),
                extremes: this.shownExtremes,
                valueTable: this.showValueTable,
                comparisonLines: this.showComparisonLines,
                hideAxisValues: this.hideAxisValues,
                detail,
                visibleRange,
                hiddenSeries: this.hiddenSeries,
                fontFamily: this.fontFamily,
            };
            const onZoom = (from, to) => this.setDateRange(from === null ? null : rangeOfTimestamps(from, to));
            // the hidden lines stay hidden, e.g. after the level of the chart was changed
            const onSeriesVisibilityChange = (key, visible) => {
                if (visible !== !this.hiddenSeries.has(key)) {
                    visible ? this.hiddenSeries.delete(key) : this.hiddenSeries.add(key);
                    this.save();
                }
            };
            // the window of a line is selected in the legend
            const onSeriesWindowChange = (key, window) => {
                const series = this.series.find(s => s.key === key);
                if (series) {
                    this.setSeriesWindow(series, window);
                    this.removeDuplicateSeries();
                    this.save();
                    this.render();
                }
            };
            // the style of a line is selected in the legend, without a style it has the style of the chart
            const onSeriesStyleChange = (key, style) => {
                this.series = this.series.map(s => {
                    if (s.key !== key) {
                        return s;
                    }
                    const {style: _, ...series} = s;
                    return style === null ? series : {...series, style};
                });
                this.save();
                this.render();
            };
            // the × of a line in the legend removes it
            const onSeriesRemove = key => {
                this.series = this.series.filter(s => s.key !== key);
                this.save();
                this.render();
            };
            const plot = createLineChart({...chartOptions, onZoom, onSeriesVisibilityChange, onSeriesWindowChange, onSeriesStyleChange, onSeriesRemove, width: dialog.chartWidth, height, container: panel});
            this.plots.push(plot);
            const title = document.title;
            const labels = chartSeries.map(s => s.label);
            const chartExport = {
                fileName: extension => exportFileName([title, ...labels], extension),
                image: () => this.lightLineChartImage(plot, chartOptions, title),
            };
            this.exports.push(chartExport);
            toolbar.append(this.exportMenu(chartExport));
            if (level !== null) {
                // the window only applies to sums and rates
                toolbar.append(compactSwitch(this.detailSwitch(chart, level)));
            }
            toolbar.append(compactSwitch(this.styleSwitch(key, chartStyle)));
            if (isRemovable) {
                toolbar.append(this.removeButton(() => {
                    this.series = this.series.filter(s => s.period !== chart.period || !chart.units.includes(s.unit));
                }));
            }
        }
        // while values are loading, the menu "Range" keeps the years of the shown values
        const years = rangeYears(shownSeries);
        this.shownYears = isLoading ? [...new Set([...years, ...(this.shownYears ?? [])])].sort((a, b) => b - a) : years;
        dialog.setDateRange(this.shownYears, this.dateRange);

        for (const pie of this.pies) {
            const panel = dialog.addPanel("chart-panel chart-pie-panel");
            const svg = () => pieChartSvg(pie, this.formatter, this.fontFamily);
            const chartExport = {
                fileName: extension => exportFileName([document.title, pie.label], extension),
                image: () => svgCanvas(svg()),
                svg,
            };
            this.exports.push(chartExport);
            const pieToolbar = this.toolbar(panel);
            pieToolbar.append(this.exportMenu(chartExport));
            if (isRemovable) {
                pieToolbar.append(this.removeButton(() => this.pies = this.pies.filter(p => p !== pie)));
            }
            renderPie(pie, panel, this.formatter);
        }

        if (this.series.length === 0 && this.pies.length === 0) {
            dialog.showEmptyHint();
        }
        // measured after the browser has laid out the charts
        dialog.setApplicableOverlays([...applicableOverlays]);
        requestAnimationFrame(() => dialog.fitChartHeights(this.plots));
    }

    /**
     * @param {object[]} seriesList The series of metrics of a line chart.
     * @param {{window: string, detail: string}} level The window and the detail level of the chart.
     * @return {object[]|null} The series with the values of the metrics, without series without values, or null if the
     *  values are still loading.
     */
    metricChartSeries(seriesList, level) {
        return seriesList.flatMap(s => {
            const window = this.seriesWindow(s, level);
            // a window shorter than the steps of the dates has finer dates, e.g. the sales per day in a chart of months
            const detail = this.seriesDetail(s, window, level);
            const data = this.metricValues[window + "/" + detail]?.data;
            const values = data?.series[metricDataId(s)];
            const points = data ? metricPoints(data, metricDataId(s)) : null;
            if (!values || points === null || points.points.size === 0) {
                return [];
            }
            const label = metricLabel({label: values.title, usesWindow: values.usesWindow}, window)
                + (s.average ? `, ${s.average}-month average` : "");
            return [{...s, ...points, label, unit: values.unit, measure: values.measure, usesWindow: values.usesWindow,
                window, selectedWindow: s.window ?? level.window, detail, period: detailPeriod(detail)}];
        });
    }

    /**
     * @return {string} The shown window of a series of a metric: its window or the window of the chart, the window,
     *  which follows the level, is the calendar window of the level.
     */
    seriesWindow(series, level) {
        // values at a date, e.g. the MRR, don't use the window, they're loaded with the window of the level
        return shownWindow(series.usesWindow ? series.window ?? level.window : "follow", level.detail);
    }

    /**
     * @return {string} The detail level of the values of a line: the level of the chart, or a finer level for a window,
     *  which is shorter than the steps of the dates, see lineDetail. Values at a date always have the level of the chart.
     */
    seriesDetail(series, window, level) {
        return series.usesWindow ? lineDetail(window, level.detail) : level.detail;
    }

    /**
     * @param {object[]} chartSeries The series of a line chart.
     * @param {string|null} detail The detail level of a chart of metrics, null for the values of tables.
     * @return {object[]} The shown trends of the series, which apply to them, as additional lines of the series.
     */
    trends(chartSeries, detail) {
        const overlays = [];
        for (const series of chartSeries) {
            for (const kind of OVERLAYS.filter(k => this.shownOverlays.includes(k) && overlayApplies(k, series, detail, series.window))) {
                if (kind === "previousYear") {
                    overlays.push({kind, base: series.key, label: `${series.label}, previous year`, points: previousYear(series).points});
                }
            }
        }
        return overlays.filter(overlay => overlay.points.size > 0);
    }

    /** @return {boolean} If values of metrics are loading. */
    isLoadingMetrics() {
        return Object.values(this.metricValues).some(entry => entry.pending);
    }

    /**
     * Loads the missing values of metrics of a window and a detail level, the values of all metrics of a level have
     * the same dates. The charts are drawn again when the values are loaded. While values are loading, the charts show
     * the values, which are already loaded.
     *
     * @param {string} chartWindow The window of the values, see WINDOWS.
     * @param {string[]} ids The IDs of the metrics and of their averages, e.g. `sales@avg3`.
     */
    loadMetrics(chartWindow, detail, ids) {
        const key = chartWindow + "/" + detail;
        const entry = this.metricValues[key] ??= {data: null, pending: false, failed: new Set()};
        const missing = [...new Set(ids)].filter(id => !entry.failed.has(id) && !(entry.data?.series[id] || entry.data?.missing.has(id)));
        // the missing values of other charts are requested when the current request is done
        if (missing.length === 0 || entry.pending) {
            return;
        }
        entry.pending = true;
        if (this.dialog?.isOpen && this.dialog.message.hidden) {
            this.dialog.showMessage(LOADING_MESSAGE);
        }

        const url = new URL("/chart-data", window.location.href);
        const pluginId = new URLSearchParams(window.location.search).get("pluginId");
        if (pluginId !== null) {
            url.searchParams.set("pluginId", pluginId);
        }
        url.searchParams.set("metrics", missing.join(","));
        url.searchParams.set("window", chartWindow);
        url.searchParams.set("detail", detail);
        fetch(url)
            .then(response => {
                if (!response.ok) {
                    throw new Error("HTTP status " + response.status);
                }
                return response.json();
            })
            .then(data => {
                // the values of other metrics of the level are kept, they have the same dates
                const series = data.dates.join() === entry.data?.dates.join() ? {...entry.data.series, ...data.series} : data.series;
                const missingIds = new Set([...(entry.data?.missing ?? []), ...missing.filter(id => !data.series[id])]);
                entry.data = {dates: data.dates, series, missing: missingIds};
                this.updateMetricSeries(data, missing);
                this.clearLoadingMessage();
            })
            .catch(error => {
                console.error("Loading the values of " + missing.join(", ") + " failed", error);
                // loaded again when the charts are opened the next time
                missing.forEach(id => entry.failed.add(id));
                this.dialog?.showMessage("The values couldn't be loaded.");
                setTimeout(() => missing.forEach(id => entry.failed.delete(id)));
            })
            .finally(() => {
                entry.pending = false;
                if (this.dialog?.isOpen) {
                    this.render();
                }
            });
    }

    /**
     * The series of metrics take the units of the loaded values, e.g. after the displayed currency changed. Series of
     * metrics without values on this page, e.g. the trials on the list of plugins, aren't shown, but they're kept for
     * other pages.
     */
    updateMetricSeries(data, requested) {
        let changed = false;
        for (const series of this.series.filter(s => s.metric && requested.includes(s.metric))) {
            const values = data.series[series.metric];
            if (values && values.unit !== series.unit) {
                series.unit = values.unit;
                changed = true;
            }
        }
        const withoutValues = this.series.filter(s => s.metric && requested.includes(s.metric) && !data.series[s.metric]);
        if (withoutValues.length > 0) {
            this.series = this.series.filter(s => !withoutValues.includes(s));
            this.unresolvedEntries.push(...withoutValues.map(metricEntry));
            changed = true;
        }
        if (changed) {
            this.save();
        }
    }

    clearLoadingMessage() {
        if (this.dialog?.message.textContent === LOADING_MESSAGE) {
            this.dialog.showMessage(null);
        }
    }

    /**
     * @return {HTMLElement} Buttons to show the values of a chart of metrics at the end of each year, month or week, or
     *  of each day.
     */
    detailSwitch(chart, level) {
        const group = document.createElement("div");
        group.className = "chart-button-group";
        group.setAttribute("role", "group");
        group.setAttribute("aria-label", "Values per");
        group.title = DETAIL_HINT;
        for (const detail of DETAILS) {
            const button = document.createElement("button");
            button.type = "button";
            button.textContent = DETAIL_LABELS[detail];
            button.setAttribute("aria-pressed", String(detail === level.detail));
            button.addEventListener("click", () => {
                // the selected window is kept, it's shown again with the levels, to which it applies
                this.setChartLevel(chart, {...storedChartLevel(chart, this.chartLevels), detail});
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
    /**
     * @param {() => void} remove Removes the series or the pie of the chart.
     * @return {HTMLButtonElement} A button to remove the chart.
     */
    removeButton(remove) {
        return removeButton("Remove this chart", () => {
            remove();
            this.save();
            this.render();
        });
    }

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
     * @return {HTMLElement} A menu to print the chart, to download it as PNG and, if available, as SVG, and to bookmark
     *  the view of the charts.
     */
    exportMenu(chartExport) {
        const items = [
            {label: "Print", run: () => this.print([chartExport])},
            // shown when the menu opens, the other charts are added after this menu
            {label: "Print all charts", run: () => this.print(this.exports), isShown: () => this.exports.length > 1},
            {label: "Download PNG", run: async () => downloadCanvas(await chartExport.image(), chartExport.fileName("png"))},
            // the same image as the download, e.g. to paste it into a message
            ...(canCopyImages() ? [{label: "Copy PNG", run: () => this.copyImage(chartExport)}] : []),
            {label: "Bookmark this view", run: () => this.bookmark()},
        ];
        // the bookmark is the last item, after the download of an SVG
        if (chartExport.svg) {
            items.splice(items.length - 1, 0, {label: "Download SVG", run: async () => downloadSvg(chartExport.svg(), chartExport.fileName("svg"))});
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

    /** Copies the image of a chart into the clipboard and shows if it was copied. */
    async copyImage(chartExport) {
        try {
            await copyCanvas(Promise.resolve(chartExport.image()));
            this.dialog.showMessage("The image of the chart was copied.");
        } catch (error) {
            console.error("Copying the image failed", error);
            this.dialog.showMessage("The image couldn't be copied.");
        }
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
     * @return {HTMLElement} Buttons to switch a line chart between lines and bars.
     */
    styleSwitch(key, currentStyle) {
        const group = document.createElement("div");
        group.className = "chart-button-group chart-style-switch";
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
