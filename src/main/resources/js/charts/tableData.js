/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

/**
 * Reads the data of the charts from the tables of the page.
 *
 * The server writes the data as attributes:
 * - tables: `data-chart-title` and `data-chart-period` (day, month or year) for line charts, `data-chart-from` and
 *   `data-chart-to` for the dates of a table of a few days, e.g. of the past 7 days
 * - column headers: `data-chart-col` (index), `data-chart-label`, `data-chart-pie` for share columns,
 *   `data-chart-measure` (flow, stock or ratio) for what the values measure,
 *   `data-chart-history` (day, month or year) for columns with the values of each row over time
 * - column headers of metrics, whose values are loaded from `/chart-data`: `data-chart-metric` (ID of the metric, the
 *   prefix of the metrics of the rows of columns with `data-chart-history`), `data-chart-unit`, and `data-chart-window`
 *   and `data-chart-detail`, the level of a new chart, which shows the values of the table
 * - cells of columns without a metric with the values of the rows over time: `data-chart-history`, JSON like
 *   `{"unit": "%", "points": [["2026-09-30", 45.2, false], ...]}`, the last value of a point is true for incomplete values
 * - rows of line charts: `data-chart-x` (ISO date), cells of columns without a metric: `data-chart-value`,
 *   `data-chart-unit`, `data-chart-incomplete`
 * - rows of pie charts: `data-chart-category`, cells: `data-chart-share` (percentage)
 * - vertical lines: `#chart-major-releases`, `#chart-price-changes`, `#chart-continuity-discount`, `#chart-ide-releases`
 *   and `#chart-custom-events`
 *   with `li[data-date]`, the custom events also with `data-event-id`
 */

import {isoDateToTimestamp, PERIODS} from "./format.js";
import {chartKey, tableWindow} from "./model.js";

function cellText(cell) {
    return cell ? cell.textContent.replace(/\s+/g, " ").trim() : "";
}

function headerLabel(header) {
    return header.dataset.chartLabel || cellText(header);
}

/**
 * @return {{header: HTMLElement, type: "line"|"pie"|"metric", table: string, column: string, columnIndex: number, isRowHistory: boolean}[]}
 *  The columns of a table, which can be shown in a chart. Columns with the values of the rows over time are line
 *  charts with a series for each row, see {@link readRowHistorySeries} and {@link readMetricSeries}.
 */
export function chartColumns(table) {
    const columns = [];
    for (const header of table.querySelectorAll("th[data-chart-col]")) {
        const columnIndex = Number.parseInt(header.dataset.chartCol, 10);
        const isRowHistory = Boolean(header.dataset.chartHistory);
        const type = header.dataset.chartPie === "true" ? "pie" : header.dataset.chartMetric ? "metric" : "line";
        const cells = `tr[data-chart-category] > :nth-child(${columnIndex + 1})`;
        // a column of a metric has a unit, if the table has values
        const hasValues = type === "metric" ? Boolean(header.dataset.chartUnit)
            : isRowHistory ? table.querySelector(`${cells}[data-chart-history]`) !== null
            : type === "pie" ? table.querySelector(`${cells}[data-chart-share]`) !== null
                : table.querySelector(`td[data-chart-col="${columnIndex}"]`) !== null;
        if (hasValues) {
            columns.push({header, type, table: table.dataset.chartTitle, column: headerLabel(header), columnIndex, isRowHistory});
        }
    }
    return columns;
}

function chartLabel(table, column) {
    return table ? column + " (" + table + ")" : column;
}

/**
 * @return {{key: string, table: string, column: string, label: string, period: string, unit: string|null,
 *  points: Map<number, number>, incomplete: Set<number>, measure: string|null}}
 *  The values of a column for a line chart.
 */
export function readLineSeries(table, columnIndex) {
    const tableTitle = table.dataset.chartTitle;
    const header = table.querySelector(`th[data-chart-col="${columnIndex}"]`);
    const column = headerLabel(header);
    const points = new Map();
    const incomplete = new Set();
    let unit = null;

    for (const row of table.querySelectorAll("tr[data-chart-x]")) {
        const cell = row.querySelector(`td[data-chart-col="${columnIndex}"]`);
        if (cell === null) {
            continue;
        }
        const timestamp = isoDateToTimestamp(row.dataset.chartX);
        unit ??= cell.dataset.chartUnit;
        points.set(timestamp, Number.parseFloat(cell.dataset.chartValue));
        if (cell.dataset.chartIncomplete === "true") {
            incomplete.add(timestamp);
        }
    }

    return {
        key: chartKey("line", tableTitle, column),
        table: tableTitle,
        column,
        label: chartLabel(tableTitle, column),
        period: PERIODS.includes(table.dataset.chartPeriod) ? table.dataset.chartPeriod : "day",
        unit,
        points,
        incomplete,
        measure: header.dataset.chartMeasure || null,
    };
}

/**
 * @return {object[]} A series for each row of a column with the values of the rows over time, like {@link readLineSeries}.
 *  The series have the name of their row as `row`, e.g. the share of the sales of each customer type per month.
 */
export function readRowHistorySeries(table, columnIndex) {
    const tableTitle = table.dataset.chartTitle;
    const header = table.querySelector(`th[data-chart-col="${columnIndex}"]`);
    const column = headerLabel(header);
    const series = [];
    for (const row of table.querySelectorAll("tr[data-chart-category]")) {
        const history = parseHistory(row.children[columnIndex]?.dataset.chartHistory);
        if (history === null) {
            continue;
        }
        const name = row.dataset.chartCategory;
        const points = new Map();
        const incomplete = new Set();
        for (const [date, value, isIncomplete] of history.points) {
            const timestamp = isoDateToTimestamp(date);
            points.set(timestamp, value);
            if (isIncomplete) {
                incomplete.add(timestamp);
            }
        }
        series.push({
            key: chartKey("line", tableTitle, column) + "/" + name,
            table: tableTitle,
            column,
            row: name,
            label: chartLabel(tableTitle, name + ", " + column),
            period: PERIODS.includes(header.dataset.chartHistory) ? header.dataset.chartHistory : "month",
            unit: history.unit,
            points,
            incomplete,
            measure: header.dataset.chartMeasure || null,
        });
    }
    return series;
}

/**
 * @return {{key: string, metric: string, window: string|null, usesWindow: boolean, label: string, unit: string,
 *  measure: string|null, row?: string}[]} The series of a column with a metric, a series for each row of a column with
 *  the values of the rows over time, e.g. the share of each customer type. The values are loaded from /chart-data, sums
 *  and rates with the window of the table.
 */
export function readMetricSeries(table, columnIndex) {
    const header = table.querySelector(`th[data-chart-col="${columnIndex}"]`);
    const column = headerLabel(header);
    const metric = header.dataset.chartMetric;
    const unit = header.dataset.chartUnit;
    const measure = header.dataset.chartMeasure || null;
    const usesWindow = header.dataset.chartUsesWindow === "true";
    // the calendar window of the level of the table follows the level of the chart, e.g. the months of a table of months
    const window = usesWindow && header.dataset.chartWindow ? tableWindow(header.dataset.chartWindow, header.dataset.chartDetail) : null;
    if (!header.dataset.chartHistory) {
        return [{key: metricKey(metric, window), metric, window, usesWindow, label: column, unit, measure}];
    }
    return [...table.querySelectorAll("tr[data-chart-category]")].map(row => {
        const name = row.dataset.chartCategory;
        const rowMetric = `${metric}.${name}`;
        return {key: metricKey(rowMetric, window), metric: rowMetric, window, usesWindow, label: name + ", " + column, unit, measure, row: name};
    });
}

/**
 * @param {string|null} [window] The window of sums and rates, e.g. the trials of each month and of each day are
 *  different series.
 * @param {number|null} [average] The months of a moving average, which the series shows instead of the values.
 * @return {string} The key of the series of a metric, the same for all tables.
 */
export function metricKey(metric, window = null, average = null) {
    return "metric:" + metric + (window ? "@" + window : "") + (average ? "~avg" + average : "");
}

/**
 * @return {{window: string, detail: string}|null} The window and the detail level of a new chart of a metric column,
 *  so that it shows the values of the table.
 */
export function readMetricLevel(table, columnIndex) {
    const header = table.querySelector(`th[data-chart-col="${columnIndex}"]`);
    const {chartWindow: window, chartDetail: detail} = header.dataset;
    return window && detail ? {window, detail} : null;
}

/**
 * @return {{from: string, to: string}|null} The dates of a table of a few days, the date range of the charts of its
 *  metrics, see {@link readMetricSeries}.
 */
export function readTableDateRange(table) {
    const {chartFrom: from, chartTo: to} = table.dataset;
    return from && to ? {from, to} : null;
}

/** @return {{unit: string, points: [string, number, boolean][]}|null} */
function parseHistory(json) {
    if (!json) {
        return null;
    }
    try {
        const history = JSON.parse(json);
        return typeof history?.unit === "string" && Array.isArray(history.points) ? history : null;
    } catch (e) {
        return null;
    }
}

/**
 * @return {{key: string, table: string, column: string, label: string, rows: {label: string, value: number, details: string[][]}[]}}
 *  The rows of a share column for a pie chart. The details are the values of the other columns of each row.
 */
export function readPieRows(table, columnIndex) {
    const tableTitle = table.dataset.chartTitle;
    const header = table.querySelector(`th[data-chart-col="${columnIndex}"]`);
    const column = headerLabel(header);
    // the first header contains the title of the table, the first column the name of the row
    const headerLabels = [...header.parentElement.children].map((th, index) => index === 0 ? "" : headerLabel(th));

    const rows = [];
    for (const row of table.querySelectorAll("tr[data-chart-category]")) {
        const value = Number.parseFloat(row.children[columnIndex]?.dataset.chartShare);
        if (Number.isNaN(value)) {
            continue;
        }
        const details = [...row.children]
            .map((cell, index) => [headerLabels[index], cellText(cell)])
            .filter(([label, text]) => label && text);
        rows.push({label: row.dataset.chartCategory, value, details});
    }

    return {key: chartKey("pie", tableTitle, column), table: tableTitle, column, label: chartLabel(tableTitle, column), rows};
}

/** The kinds of vertical lines and how their labels are created from the text of the list items. */
const MARKER_SOURCES = [
    {kind: "release", selector: "#chart-major-releases li", label: text => "v" + text},
    {kind: "price", selector: "#chart-price-changes li", label: text => text},
    {kind: "continuity", selector: "#chart-continuity-discount li", label: text => text},
    {kind: "ide", selector: "#chart-ide-releases li", label: text => "IDEA " + text},
    {kind: "custom", selector: "#chart-custom-events li", label: text => text},
];

/**
 * @return {{kind: "release"|"price"|"continuity"|"ide"|"custom", timestamp: number, label: string, id?: string}[]} The vertical lines
 *  of the line charts: major releases of the plugin, changes of its base price, the date at which it disabled
 *  the continuity discount, major releases of IntelliJ IDEA and the
 *  custom events of the configured file, which have an ID.
 */
export function readMarkers(document) {
    return MARKER_SOURCES.flatMap(({kind, selector, label}) => [...document.querySelectorAll(selector)].map(item => ({
        kind,
        timestamp: isoDateToTimestamp(item.dataset.date),
        label: label(item.textContent.trim()),
        ...(item.dataset.eventId ? {id: item.dataset.eventId} : {}),
    })));
}
