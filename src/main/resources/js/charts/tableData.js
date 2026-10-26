/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

/**
 * Reads the data of the charts from the tables of the page.
 *
 * The server writes the data as attributes:
 * - tables: `data-chart-title` and `data-chart-period` (day, month or year) for line charts
 * - column headers: `data-chart-col` (index), `data-chart-label`, `data-chart-pie` for share columns and
 *   `data-chart-daily` for columns with values for each day, which are loaded from `/chart-series`,
 *   `data-chart-measure` (flow, stock or ratio) for what the values measure,
 *   `data-chart-history` (day, month or year) for columns with the values of each row over time
 * - cells of columns with the values of the rows over time: `data-chart-history`, JSON like
 *   `{"unit": "%", "points": [["2026-09-30", 45.2, false], ...]}`, the last value of a point is true for incomplete values
 * - rows of line charts: `data-chart-x` (ISO date), cells: `data-chart-value`, `data-chart-unit`, `data-chart-incomplete`
 * - rows of pie charts: `data-chart-category`, cells: `data-chart-share` (percentage)
 * - vertical lines: `#chart-major-releases`, `#chart-price-changes`, `#chart-ide-releases` and `#chart-custom-events`
 *   with `li[data-date]`, the custom events also with `data-event-id`
 */

import {isoDateToTimestamp, PERIODS} from "./format.js";
import {chartKey} from "./model.js";

function cellText(cell) {
    return cell ? cell.textContent.replace(/\s+/g, " ").trim() : "";
}

function headerLabel(header) {
    return header.dataset.chartLabel || cellText(header);
}

/**
 * @return {{header: HTMLElement, type: "line"|"pie", table: string, column: string, columnIndex: number, isRowHistory: boolean}[]}
 *  The columns of a table, which can be shown in a chart. Columns with the values of the rows over time are line
 *  charts with a series for each row, see {@link readRowHistorySeries}.
 */
export function chartColumns(table) {
    const columns = [];
    for (const header of table.querySelectorAll("th[data-chart-col]")) {
        const columnIndex = Number.parseInt(header.dataset.chartCol, 10);
        const isRowHistory = Boolean(header.dataset.chartHistory);
        const type = header.dataset.chartPie === "true" ? "pie" : "line";
        const cells = `tr[data-chart-category] > :nth-child(${columnIndex + 1})`;
        const hasValues = isRowHistory ? table.querySelector(`${cells}[data-chart-history]`) !== null
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
 *  points: Map<number, number>, incomplete: Set<number>, daily: string|null, measure: string|null}}
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
        daily: header.dataset.chartDaily || null,
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
            // the values per day and per year, e.g. customer-type-share.month.Individual, see CustomerTypeDailyValues.kt
            daily: header.dataset.chartDaily ? `${header.dataset.chartDaily}.month.${name}` : null,
            yearly: header.dataset.chartDaily ? `${header.dataset.chartDaily}.year.${name}` : null,
            measure: header.dataset.chartMeasure || null,
        });
    }
    return series;
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
    {kind: "ide", selector: "#chart-ide-releases li", label: text => "IDEA " + text},
    {kind: "custom", selector: "#chart-custom-events li", label: text => text},
];

/**
 * @return {{kind: "release"|"price"|"ide"|"custom", timestamp: number, label: string, id?: string}[]} The vertical lines
 *  of the line charts: major releases of the plugin, changes of its base price, major releases of IntelliJ IDEA and the
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
