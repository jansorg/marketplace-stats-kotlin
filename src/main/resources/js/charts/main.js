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

import {fillAddMenu} from "./addMenu.js";
import {compactSwitch, detailSwitch, styleSwitch} from "./chartControls.js";
import {exportMenu, lightLineChartImage} from "./chartExports.js";
import {ChartDialog, removeButton} from "./dialog.js";
import {fetchMetrics, fetchMetricValues, fetchParameterValues} from "./endpoints.js";
import {pieChartSvg, svgCanvas} from "./exportImage.js";
import {createFormatter} from "./format.js";
import {createLineChart} from "./lineChart.js";
import {isAbortError, metricDataId, metricEntry, metricSeries, MetricValueCache} from "./metricValues.js";
import {
    canCombineUnits, chartExportUrl, chartLevel, chartLevelKeys, dateRange, detailGranularity, detailPeriod, exportFileName, familyMetricId, familyOfMetric,
    allowedWindow, finestDetail, groupCharts, groupUnits, loadedRangeStart, parameterSections, parameterValuesKey,
    lineChartKey, lineDetail, MARKER_KINDS, MAX_PIES, MAX_SERIES, METRIC_PERIOD, metricLabel, metricPoints, nextColor, overlayApplies, OVERLAYS,
    pieSlices, previousYear, rangeLabel, rangeOfPreset, rangeOfTimestamps, rangeTimestamps, rangeYears, seriesStyle, shownWindow, storedChartLevel,
    visibleMarkers, withoutIncomplete,
} from "./model.js";
import {enableShortcut, makeColumnClickable, makeHintsClickable, makeLogoClickable, makeRowChartButtons} from "./pageControls.js";
import {renderPie} from "./pieChart.js";
import {browserStorage, createSetupStorage, decodeView, emptySetup, encodeView} from "./storage.js";
import {chartColumns, metricKey, readLineSeries, readMarkers, readMetricLevel, readMetricSeries, readPieRows, readRowHistorySeries, readTableDateRange} from "./tableData.js";

const LOADING_MESSAGE = "Loading the values…";
/** The values of metrics of the open chart dialog are loaded again after this time, e.g. to show new sales. */
const REFRESH_INTERVAL = 5 * 60 * 1000;

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
        // the y-axes are logarithmic, see createLineChart
        this.logScale = false;
        // the shown dates of the line charts, null for all dates, see storage.js
        this.dateRange = null;
        // the keys of the series and overlays, which were hidden by a click on the legend, see createLineChart
        this.hiddenSeries = new Set();
        // the years of the menu "Range", see render
        this.shownYears = [];
        // if the charts are drawn in the next frame, see setDateRange
        this.isRenderPending = false;
        // trends of the line charts, see OVERLAYS
        this.shownOverlays = [];
        this.hasLineCharts = false;
        // each plugin and the summary of all plugins have their own setup, see data-plugin-id of the templates
        this.storage = createSetupStorage(browserStorage(), document.body.dataset.pluginId ?? null);

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
        this.metricValues = new MetricValueCache(fetchMetricValues);
        // the time of the latest load of the values of metrics, see refreshMetricValues
        this.refreshedAt = Date.now();
        this.shownStyles = {};
        this.dialog = null;
        // the families of metrics with parameters of /chart-metrics, null until they're loaded, see loadFamilies
        this.families = null;
        // the loaded values of the parameters of the families by parameterValuesKey, see loadParameterValues
        this.parameterValues = {};
        this.loadingParameters = new Set();
    }

    start() {
        const columns = [...document.querySelectorAll("table[data-chart-title]")].flatMap(table => {
            const tableColumns = chartColumns(table).filter(column => column.type === "pie" || this.lineChartsAvailable);
            tableColumns.forEach(column => makeColumnClickable(column, table, () => this.showColumn(column, table)));
            this.hasLineCharts ||= tableColumns.some(column => column.type !== "pie");
            return tableColumns.map(column => ({...column, element: table}));
        });
        const isBookmarkedView = this.restore(columns);
        makeLogoClickable(() => this.openDialog(null));
        makeHintsClickable(() => this.openDialog(null));
        const hasRowCharts = this.lineChartsAvailable && makeRowChartButtons(metric => this.showRowMetric(metric));
        if (columns.length > 0 || hasRowCharts) {
            enableShortcut(() => this.dialog?.isOpen ? this.dialog.close() : this.openDialog(null));
        }
        // a bookmarked view replaces the stored setup and is shown
        if (isBookmarkedView) {
            this.save();
            this.openDialog(null);
        }
        // the colors of the charts follow the theme of the page, see siteFooter.kte
        document.addEventListener("marketplace-themechange", () => {
            if (this.dialog?.isOpen) {
                this.render();
            }
        });
        // the open charts show new values of metrics, also after a hidden tab is shown again
        setInterval(() => this.refreshMetricValues(), 60 * 1000);
        document.addEventListener("visibilitychange", () => this.refreshMetricValues());
    }

    /**
     * Loads the values of metrics again, if the chart dialog is open and visible and the values are older than
     * {@link REFRESH_INTERVAL}. The charts are only drawn again, if the values changed, e.g. an open menu stays open
     * otherwise. The values of the tables are the ones of the page.
     */
    refreshMetricValues() {
        if (!this.dialog?.isOpen || document.hidden || Date.now() - this.refreshedAt < REFRESH_INTERVAL) {
            return;
        }
        this.refreshedAt = Date.now();
        this.metricValues.refresh().then(({changed, responses}) => {
            responses.forEach(({data, requested}) => this.updateMetricSeries(data, requested));
            if (!this.dialog?.isOpen) {
                return;
            }
            if (changed) {
                this.render();
            } else {
                // values, which were requested while the values were loaded again
                this.requestMetricValues(groupCharts(this.series, this.combineUnits));
            }
        });
    }

    /** Shows a column of a table in a chart, see chartColumns. */
    showColumn({type, columnIndex, isRowHistory}, table) {
        if (type === "pie") {
            this.addPie(table, columnIndex);
        } else if (type === "metric") {
            this.addMetricSeries(table, columnIndex);
        } else if (isRowHistory) {
            this.addRowHistorySeries(table, columnIndex);
        } else {
            this.addSeries(table, columnIndex);
        }
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
            this.series.push({...series, color: this.colorOf(series)});
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
            this.series.push({...series, color: this.colorOf(series)});
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
            this.series.push(metricSeries(series, this.colorOf(metricSeries(series))));
        }
        this.save();
        this.openDialog(rejection);
    }

    /**
     * Shows the metric of a row of a table, e.g. the discount of an offer, in the chart of its unit, with the window of
     * the level of the chart. A metric, which is already shown, is only shown.
     *
     * @param {{id: string, title: string, unit: string}} metric The metric of the button of the row.
     */
    showRowMetric(metric) {
        const uncolored = metricSeries({metric: metric.id, window: "follow", usesWindow: true, label: metric.title,
            unit: metric.unit, measure: "flow"});
        const rejection = this.rejectSeries(uncolored);
        if (rejection === null) {
            this.series.push({...uncolored, color: this.colorOf(uncolored)});
            this.save();
        }
        this.openDialog(rejection || null);
    }

    /**
     * @return {Promise<object[]>} The families of metrics with parameters of /chart-metrics, loaded once. Without the
     *  metrics, there are none.
     */
    loadFamilies() {
        this.familiesRequest ??= fetchMetrics()
            .then(metrics => metrics.filter(metric => metric.parameters))
            .catch(error => {
                console.error("Loading the metrics failed", error);
                return [];
            })
            .then(families => {
                this.families = families;
                return families;
            });
        return this.familiesRequest;
    }

    /**
     * Loads the values of the parameters of a family once, e.g. the releases, and draws the charts again, so that the
     * legend can select them.
     */
    loadParameterValues(family) {
        for (const parameter of family.parameters) {
            const key = parameterValuesKey(family.id, parameter.id);
            if (this.parameterValues[key] || this.loadingParameters.has(key)) {
                continue;
            }
            this.loadingParameters.add(key);
            fetchParameterValues(family.id, parameter.id)
                .then(values => {
                    this.parameterValues[key] = values;
                    if (this.dialog?.isOpen) {
                        this.render();
                    }
                })
                .catch(error => console.error("Loading the values of the parameter failed", key, error));
        }
    }

    /** Changes the value of a parameter of a series of a metric of a family, e.g. to show another release. */
    setSeriesParameter(series, index, value) {
        const familyMetric = familyOfMetric(series.metric, this.families ?? []);
        if (familyMetric === null) {
            return;
        }
        const values = familyMetric.values.map((current, i) => i === index ? value : current);
        series.metric = familyMetricId(familyMetric.family.id, values);
        series.key = metricKey(series.metric, series.window, series.average, series.copy);
        // the label until the values with the title of the server are loaded, e.g. "Downloads of a release: 1.0, IntelliJ IDEA"
        const labels = familyMetric.family.parameters.flatMap((parameter, i) => values[i] === null ? []
            : this.parameterValues[parameterValuesKey(familyMetric.family.id, parameter.id)]?.find(v => v.value === values[i])?.label ?? values[i]);
        series.label = `${familyMetric.family.title}: ${labels.join(", ")}`;
    }

    /**
     * Adds a copy of a line of a metric after it, e.g. to show another release or window next to it. The copy has a
     * number, which is part of its key, and the next free color.
     */
    duplicateSeries(series) {
        const copies = this.series.filter(s => s.metric === series.metric).map(s => s.copy ?? 1);
        const uncolored = metricSeries({...series, copy: Math.max(...copies) + 1});
        const copy = {...uncolored, color: this.colorOf(uncolored)};
        const rejection = this.rejectSeries(copy);
        if (rejection === null) {
            this.series.splice(this.series.indexOf(series) + 1, 0, copy);
            this.save();
        }
        this.dialog?.showMessage(rejection || null);
        this.render();
    }

    /**
     * Adds a metric of the menu "Add" to the chart of its unit, sums and rates with the window.
     *
     * @param {{id: string, title: string, unit: string, measure: string, usesWindow: boolean, description?: string}} metric
     *  A metric of /chart-metrics.
     */
    addMetric(metric, window, average = null) {
        // e.g. the unique downloads have no values of the window of the last 12 months
        const uncolored = metricSeries({metric: metric.id, window: allowedWindow(window, metric.windows), usesWindow: metric.usesWindow, average, label: metric.title,
            unit: metric.unit, measure: metric.measure});
        const series = {...uncolored, color: this.colorOf(uncolored)};
        const rejection = this.rejectSeries(series);
        if (rejection === null) {
            this.series.push(series);
            this.save();
        }
        this.dialog.showMessage(rejection || null);
        this.render();
    }

    /** Changes the window of a series of a metric, e.g. to show the trials of each day instead of each month. */
    setSeriesWindow(series, window) {
        series.window = window;
        series.key = metricKey(series.metric, window, series.average, series.copy);
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
     * options of the menus, also of the other pages of the plugin. The setups of other plugins and of the summary of
     * all plugins are unchanged.
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
        this.logScale = setup.logScale;
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
                const uncolored = metricSeries({...entry, window});
                const series = {...uncolored, color: this.colorOf(uncolored, entry.color)};
                // line charts need uPlot
                if (!this.lineChartsAvailable) {
                    this.unresolvedEntries.push(entry);
                } else if (this.rejectSeries(series) === null) {
                    this.series.push(series);
                }
                continue;
            }
            const column = columns.find(c => c.type === entry.type && c.table === entry.table && c.column === entry.column);
            if (column === undefined) {
                this.unresolvedEntries.push(entry);
            } else if (entry.type === "pie") {
                const pie = this.readPie(column.element, column.columnIndex);
                if (this.rejectPie(pie) === null) {
                    this.pies.push(pie);
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
                    const styled = {...series, ...(entry.style ? {style: entry.style} : {})};
                    this.series.push({...styled, color: this.colorOf(styled, entry.color)});
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
            logScale: this.logScale,
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
                onCombineUnitsChange: combine => this.setOption("combineUnits", combine),
                onMaximizedChange: maximized => this.setOption("maximized", maximized),
                onResize: () => this.resize(),
                onMarkerVisibilityChange: (kind, visible) => this.toggleListOption("shownMarkers", kind, visible),
                onEventVisibilityChange: (id, visible) => this.toggleListOption("shownEvents", id, visible),
                onExtremeVisibilityChange: (kind, visible) => this.toggleListOption("shownExtremes", kind, visible),
                onOverlayVisibilityChange: (kind, visible) => {
                    this.toggleListOption("shownOverlays", kind, visible);
                    // an option without effect is explained, the values of the averages may still load
                    if (visible && !this.metricValues.isLoading && !this.dialog.isOverlayApplicable(kind)) {
                        this.dialog.showMessage(this.dialog.overlayHint(kind));
                    }
                },
                onDateRangeChange: range => this.setDateRange(range),
                onAddMenuOpen: (items, close) => fillAddMenu(items, {
                    selectedWindow: this.addWindow,
                    onWindowChange: window => this.addWindow = window,
                    onAdd: (metric, window, average) => this.addMetric(metric, window, average),
                    onError: () => this.dialog.showMessage("The metrics couldn't be loaded."),
                    close,
                }),
                onValueTableVisibilityChange: visible => this.setOption("showValueTable", visible),
                onHideAxisValuesChange: hidden => this.setOption("hideAxisValues", hidden),
                onLogScaleChange: logarithmic => this.setOption("logScale", logarithmic),
                onComparisonLinesVisibilityChange: visible => this.setOption("showComparisonLines", visible),
                onIncompleteVisibilityChange: visible => this.setOption("showIncomplete", visible),
            }, MARKER_KINDS.filter(kind => this.markers.some(marker => marker.kind === kind)), this.hasLineCharts,
            this.markers.filter(marker => marker.kind === "custom"));
            // the values aren't needed anymore, the server stops loading them, e.g. the downloads of a release
            this.dialog.element.addEventListener("close", () => this.metricValues.abort());
        }
        return this.dialog;
    }

    /** Sets an option of the charts, e.g. `showIncomplete`, stores it and draws the charts again. */
    setOption(name, value) {
        this[name] = value;
        this.save();
        this.render();
    }

    /** Adds a value to an option with a list of values, e.g. `shownMarkers`, or removes it, see {@link setOption}. */
    toggleListOption(name, value, isAdded) {
        this.setOption(name, isAdded ? [...new Set([...this[name], value])] : this[name].filter(v => v !== value));
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
        // the values of metrics, which were loaded before the dialog was closed, may be old
        this.refreshMetricValues();
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
        dialog.setLogScale(this.logScale);
        dialog.setShownOverlays(this.shownOverlays);

        const charts = groupCharts(this.series, this.combineUnits);
        dialog.setLineChartsShown(charts.length > 0);
        this.requestMetricValues(charts);
        const context = {
            markers: visibleMarkers(this.markers, this.shownMarkers, this.shownEvents),
            // with several charts, each chart can be removed
            isRemovable: charts.length + this.pies.length > 1,
            visibleRange: rangeTimestamps(this.dateRange),
            height: dialog.chartHeight(charts.length),
            // the trends, which apply to at least one series of the shown line charts
            applicableOverlays: new Set(),
        };
        // the styles of the shown charts, a new style of one chart must not change the others
        this.shownStyles = {};
        // the series with values, e.g. for the years of the menu "Range"
        const shownSeries = [];
        // values of metrics, which are still loading, e.g. after a change of the level
        let isLoading = false;
        for (const chart of charts) {
            const chartSeries = this.renderLineChart(chart, context);
            if (chartSeries === null) {
                isLoading ||= this.metricValues.isLoading;
            } else {
                shownSeries.push(...chartSeries);
            }
        }
        // while values are loading, the menu "Range" keeps the years of the shown values
        const years = rangeYears(shownSeries);
        this.shownYears = isLoading ? [...new Set([...years, ...this.shownYears])].sort((a, b) => b - a) : years;
        dialog.setDateRange(this.shownYears, this.dateRange);

        for (const pie of this.pies) {
            this.renderPieChart(pie, context.isRemovable);
        }
        if (this.series.length === 0 && this.pies.length === 0) {
            dialog.showEmptyHint();
        }
        dialog.setApplicableOverlays([...context.applicableOverlays]);
        // measured after the browser has laid out the charts
        requestAnimationFrame(() => dialog.fitChartHeights(this.plots));
    }

    /**
     * Requests the missing values of the charts of metrics, one request for each window and detail level with the
     * values of all charts.
     */
    requestMetricValues(charts) {
        const requests = new Map();
        for (const chart of charts.filter(c => c.period === METRIC_PERIOD)) {
            const level = chartLevel(chart, this.chartLevels);
            for (const s of this.chartSeriesOf(chart)) {
                const window = this.seriesWindow(s, level);
                const detail = this.seriesDetail(s, window, level);
                const key = window + "/" + detail;
                requests.set(key, {window, detail, ids: [...(requests.get(key)?.ids ?? []), metricDataId(s)]});
            }
        }
        requests.forEach(({window, detail, ids}) => this.loadMetrics(window, detail, ids));
    }

    /**
     * @param {object} series A new series without a color.
     * @param {string} [preferredColor] The stored color of the series.
     * @return {string|undefined} The color of the new series, which is distinct from the colors of its chart, see
     *  {@link nextColor}.
     */
    colorOf(series, preferredColor = undefined) {
        const periodSeries = this.series.filter(s => s.period === series.period);
        const units = groupUnits([...new Set([...periodSeries.map(s => s.unit), series.unit])], this.combineUnits)
            .find(units => units.includes(series.unit));
        const chartStyle = this.chartStyles[lineChartKey({period: series.period, units})] ?? this.preferredStyle;
        const neighbors = periodSeries.filter(s => units.includes(s.unit)).map(s => ({color: s.color, style: seriesStyle(s, chartStyle)}));
        return nextColor(this.series.map(s => s.color), preferredColor, neighbors, seriesStyle(series, chartStyle));
    }

    /** @return {object[]} The series of a line chart of {@link groupCharts}. */
    chartSeriesOf(chart) {
        return this.series.filter(s => s.period === chart.period && chart.units.includes(s.unit));
    }

    /**
     * Adds a line chart with its toolbar to the dialog.
     *
     * @param {{period: string, units: string[], dateRange: [number, number]|null}} chart A chart of {@link groupCharts}.
     * @param {{markers: object[], isRemovable: boolean, visibleRange: [number, number]|null, height: number,
     *  applicableOverlays: Set<string>}} context The options of all charts, the trends, which apply to the series of
     *  the chart, are added to `applicableOverlays`.
     * @return {object[]|null} The shown series of the chart, null if the chart isn't shown: the values of its metrics
     *  are still loading, or the metrics have no values.
     */
    renderLineChart(chart, context) {
        const dialog = this.dialog;
        const key = lineChartKey(chart);
        const level = chart.period === METRIC_PERIOD ? chartLevel(chart, this.chartLevels) : null;
        let chartSeries = this.chartSeriesOf(chart);
        if (level !== null) {
            chartSeries = this.metricChartSeries(chartSeries, level);
            if (chartSeries.length === 0) {
                return null;
            }
        }
        const shownSeries = chartSeries;
        if (!this.showIncomplete) {
            chartSeries = chartSeries.map(withoutIncomplete);
        }
        const detail = level?.detail ?? null;
        const chartStyle = this.chartStyles[key] ?? this.preferredStyle;
        this.shownStyles[key] = chartStyle;
        for (const series of chartSeries) {
            OVERLAYS.filter(kind => overlayApplies(kind, series, detail, level?.window)).forEach(kind => context.applicableOverlays.add(kind));
        }
        // charts of metrics and charts without incomplete values have their own date range
        const chartDateRange = (level === null && this.showIncomplete
            ? chart.dateRange
            : dateRange(chartSeries) ?? chart.dateRange)
            // the lines of the chart are loading, e.g. after another release was selected: the shown range or this year
            ?? context.visibleRange ?? rangeTimestamps(rangeOfPreset("this-year"));
        const panel = dialog.addPanel("chart-panel");
        const toolbar = this.toolbar(panel);
        const chartOptions = {
            units: chart.units,
            series: chartSeries,
            overlays: this.trends(chartSeries, detail),
            dateRange: chartDateRange,
            markers: context.markers,
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
            logScale: this.logScale,
            detail,
            visibleRange: context.visibleRange,
            hiddenSeries: this.hiddenSeries,
            fontFamily: this.fontFamily,
        };
        const plot = createLineChart({...chartOptions, ...this.lineChartCallbacks(), width: dialog.chartWidth, height: context.height, container: panel});
        this.plots.push(plot);
        const title = document.title;
        const chartExport = {
            fileName: extension => exportFileName([title, ...chartSeries.map(s => s.label)], extension),
            image: options => lightLineChartImage(plot, chartOptions, {...options, title, fontFamily: this.fontFamily}),
            // the values of the shown lines of metrics, the values of tables have no address
            ...(level !== null ? {dataUrl: () => chartExportUrl(window.location.href, {
                series: chartSeries, detail, shownOverlays: this.shownOverlays, hiddenSeries: this.hiddenSeries,
                dateRange: this.dateRange, showIncomplete: this.showIncomplete,
            })} : {}),
        };
        this.exports.push(chartExport);
        toolbar.append(this.exportMenu(chartExport));
        if (level !== null) {
            toolbar.append(compactSwitch(detailSwitch(level.detail, detail => {
                // the selected window is kept, it's shown again with the levels, to which it applies
                this.setChartLevel(chart, {...storedChartLevel(chart, this.chartLevels), detail});
                this.save();
                this.render();
            })));
        }
        toolbar.append(compactSwitch(styleSwitch(chartStyle, style => {
            // only this chart changes, the other charts keep their current style
            this.chartStyles = {...this.chartStyles, ...this.shownStyles, [key]: style};
            this.preferredStyle = style;
            this.save();
            this.render();
        })));
        if (context.isRemovable) {
            toolbar.append(this.removeButton(() => {
                this.series = this.series.filter(s => s.period !== chart.period || !chart.units.includes(s.unit));
            }));
        }
        return shownSeries;
    }

    /** @return {object} The callbacks of the legend and the zoom of a line chart, see createLineChart. */
    lineChartCallbacks() {
        return {
            onZoom: (from, to) => this.setDateRange(from === null ? null : rangeOfTimestamps(from, to)),
            // the hidden lines stay hidden, e.g. after the level of the chart was changed
            onSeriesVisibilityChange: (key, visible) => {
                if (visible !== !this.hiddenSeries.has(key)) {
                    visible ? this.hiddenSeries.delete(key) : this.hiddenSeries.add(key);
                    this.save();
                }
            },
            // the window of a line is selected in the legend
            onSeriesWindowChange: (key, window) => {
                const series = this.series.find(s => s.key === key);
                if (series) {
                    this.setSeriesWindow(series, window);
                    this.removeDuplicateSeries();
                    this.save();
                    this.render();
                }
            },
            // the value of a parameter of a line is selected in the legend, e.g. another release
            onSeriesParameterChange: (key, index, value) => {
                const series = this.series.find(s => s.key === key);
                if (series) {
                    this.setSeriesParameter(series, index, value);
                    this.removeDuplicateSeries();
                    this.save();
                    this.render();
                }
            },
            // the style of a line is selected in the legend, without a style it has the style of the chart
            onSeriesStyleChange: (key, style) => {
                this.series = this.series.map(s => {
                    if (s.key !== key) {
                        return s;
                    }
                    const {style: _, ...series} = s;
                    return style === null ? series : {...series, style};
                });
                this.save();
                this.render();
            },
            // the duplicate button of a line of a metric in the legend adds a copy of it
            onSeriesDuplicate: key => {
                const series = this.series.find(s => s.key === key);
                if (series?.metric) {
                    this.duplicateSeries(series);
                }
            },
            // the × of a line in the legend removes it
            onSeriesRemove: key => {
                this.series = this.series.filter(s => s.key !== key);
                this.save();
                this.render();
            },
        };
    }

    /** Adds a pie chart with its toolbar to the dialog. */
    renderPieChart(pie, isRemovable) {
        const panel = this.dialog.addPanel("chart-panel chart-pie-panel");
        const svg = options => pieChartSvg(pie, this.formatter, this.fontFamily, options);
        const chartExport = {
            fileName: extension => exportFileName([document.title, pie.label], extension),
            image: () => svgCanvas(svg()),
            svg,
        };
        this.exports.push(chartExport);
        const toolbar = this.toolbar(panel);
        toolbar.append(this.exportMenu(chartExport));
        if (isRemovable) {
            toolbar.append(this.removeButton(() => this.pies = this.pies.filter(p => p !== pie)));
        }
        renderPie(pie, panel, this.formatter);
    }

    /**
     * @param {object[]} seriesList The series of metrics of a line chart.
     * @param {{window: string, detail: string}} level The window and the detail level of the chart.
     * @return {object[]} The series with the values of the metrics, without series without values, e.g. while their
     *  values are loading.
     */
    metricChartSeries(seriesList, level) {
        return seriesList.flatMap(s => {
            const window = this.seriesWindow(s, level);
            // a window shorter than the steps of the dates has finer dates, e.g. the sales per day in a chart of months
            const detail = this.seriesDetail(s, window, level);
            const data = this.metricValues.data(window, detail);
            const values = data?.series[metricDataId(s)];
            const points = data ? metricPoints(data, metricDataId(s)) : null;
            if (!values || points === null || points.points.size === 0) {
                // While the values are loading, e.g. after another release was selected in the legend, the line stays
                // in the legend without points. A line without values after loading isn't shown, e.g. the trials on
                // the list of plugins.
                if (this.metricValues.isKnownWithoutValues(window, detail, metricDataId(s))) {
                    return [];
                }
                return [{...s, points: new Map(), incomplete: new Set(), label: s.label, window, selectedWindow: s.window ?? level.window, detail,
                    period: detailPeriod(detail), parameterSections: this.parameterSectionsOf(s)}];
            }
            // the window of the level, e.g. the calendar month at the level Month, isn't part of the label
            const label = metricLabel({label: values.title, usesWindow: values.usesWindow}, window, shownWindow("follow", level.detail))
                + (s.average ? `, ${s.average}-month average` : "");
            return [{...s, ...points, label, unit: values.unit, measure: values.measure, usesWindow: values.usesWindow, fractionDigits: values.fractionDigits,
                ...(values.windows ? {windows: values.windows} : {}),
                window, selectedWindow: s.window ?? level.window, detail, period: detailPeriod(detail), parameterSections: this.parameterSectionsOf(s)}];
        });
    }

    /**
     * @return {object[]} The sections of the menu of a line of a metric of a family, which select the values of its
     *  parameters, see parameterSections in model.js. The families and the values are loaded when they're needed.
     */
    parameterSectionsOf(series) {
        if (this.families === null) {
            this.loadFamilies().then(families => families.length > 0 && this.dialog?.isOpen && this.render());
            return [];
        }
        const familyMetric = familyOfMetric(series.metric, this.families);
        if (familyMetric === null) {
            return [];
        }
        this.loadParameterValues(familyMetric.family);
        return parameterSections(familyMetric, this.parameterValues);
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
        // a line without points, e.g. while its values are loading, has no trends
        for (const series of chartSeries.filter(s => s.points.size > 0)) {
            for (const kind of OVERLAYS.filter(k => this.shownOverlays.includes(k) && overlayApplies(k, series, detail, series.window))) {
                if (kind === "previousYear") {
                    overlays.push({kind, base: series.key, label: `${series.label}, previous year`, points: previousYear(series).points});
                }
            }
        }
        return overlays.filter(overlay => overlay.points.size > 0);
    }

    /**
     * Loads the missing values of metrics of a window and a detail level, see MetricValueCache. The charts are drawn
     * again when the values are loaded. While values are loading, the charts show the values, which are already loaded.
     *
     * @param {string} chartWindow The window of the values, see WINDOWS.
     * @param {string[]} ids The IDs of the metrics and of their averages, e.g. `sales@avg3`.
     */
    loadMetrics(chartWindow, detail, ids) {
        // e.g. the downloads of a release are only loaded from the start of the shown range, they take long to load,
        // the trend of the previous year needs the range of the previous year
        const request = this.metricValues.load(chartWindow, detail, ids, loadedRangeStart(this.dateRange, this.shownOverlays));
        if (request === null) {
            return;
        }
        if (this.dialog?.isOpen && this.dialog.shownMessage === null) {
            this.dialog.showMessage(LOADING_MESSAGE, {persistent: true});
        }
        request
            .then(({data, requested}) => {
                this.updateMetricSeries(data, requested);
                this.clearLoadingMessage();
            })
            .catch(error => {
                // the requests are aborted, when the dialog is closed
                if (isAbortError(error)) {
                    this.clearLoadingMessage();
                    return;
                }
                console.error("Loading the values of metrics failed", error);
                this.dialog?.showMessage("The values couldn't be loaded.");
            })
            .finally(() => {
                if (this.dialog?.isOpen) {
                    this.render();
                }
            });
    }

    /**
     * The series of metrics take the units of the loaded values, e.g. after the displayed currency changed. Series of
     * metrics without values on this page, e.g. the trials on the list of plugins, aren't shown, but they're kept for
     * other pages. The values of moving averages have their own IDs, see metricDataId.
     */
    updateMetricSeries(data, requested) {
        let changed = false;
        const requestedSeries = this.series.filter(s => s.metric && requested.includes(metricDataId(s)));
        for (const series of requestedSeries) {
            const values = data.series[metricDataId(series)];
            if (values && values.unit !== series.unit) {
                series.unit = values.unit;
                changed = true;
            }
        }
        const withoutValues = requestedSeries.filter(s => !data.series[metricDataId(s)]);
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
        if (this.dialog?.shownMessage === LOADING_MESSAGE) {
            this.dialog.showMessage(null);
        }
    }

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

    /** @return {HTMLElement} The menu "Export" of a chart, see chartExports.js. */
    exportMenu(chartExport) {
        return exportMenu(chartExport, {
            allExports: () => this.exports,
            bookmark: () => this.bookmark(),
            showMessage: (text, options) => this.dialog.showMessage(text, options),
        });
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
