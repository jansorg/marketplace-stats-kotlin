/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

/**
 * The series of metrics and their values, which are loaded from /chart-data. They don't access the DOM and are tested
 * with Node.js.
 */

import {METRIC_PERIOD} from "./model.js";
import {metricKey} from "./tableData.js";

/**
 * @param {{metric: string, window?: string|null, usesWindow?: boolean, average?: number, label: string, unit: string,
 *  measure?: string|null, row?: string, style?: string, copy?: number}} series A series of a metric column, of the
 *  menu to add metrics or of the stored setup. Sums and rates have a window, e.g. the trials of each month and of each
 *  day are two series. A copy of a line has a number, so that it can be changed independently of the line.
 * @param {string} color
 * @return {object} The series of the metric, its values are loaded when it's shown, see ChartController.metricChartSeries.
 */
export function metricSeries({metric, window, usesWindow, average, label, unit, measure, row, style, copy}, color) {
    const hasWindow = usesWindow ?? measure !== "stock";
    const seriesWindow = hasWindow ? window ?? null : null;
    return {
        key: metricKey(metric, seriesWindow, average, copy), metric, window: seriesWindow, usesWindow: hasWindow,
        ...(average ? {average} : {}), ...(copy ? {copy} : {}), label, unit, measure: measure ?? null, ...(row ? {row} : {}),
        period: METRIC_PERIOD, points: new Map(), incomplete: new Set(), color, ...(style ? {style} : {}),
    };
}

/**
 * @return {string} The ID of the values of a series of a metric in /chart-data: the metric or its moving average, e.g.
 *  `sales@avg3`.
 */
export function metricDataId(series) {
    return series.average ? `${series.metric}@avg${series.average}` : series.metric;
}

/** @return {import("./storage.js").ChartEntry} The stored entry of a series of a metric, see {@link metricSeries}. */
export function metricEntry(s) {
    return {type: "metric", metric: s.metric, ...(s.window ? {window: s.window} : {}), usesWindow: s.usesWindow, ...(s.average ? {average: s.average} : {}),
        ...(s.copy ? {copy: s.copy} : {}), label: s.label, unit: s.unit,
        ...(s.measure ? {measure: s.measure} : {}), ...(s.row ? {row: s.row} : {}), color: s.color, ...(s.style ? {style: s.style} : {})};
}

/**
 * @typedef {{dates: string[], series: Object<string, object>, missing: Set<string>}} MetricData The loaded values of
 *  the metrics of a window and a detail level, they all have the same dates. `missing` are the requested IDs without
 *  values, e.g. the trials on the list of plugins.
 */

/**
 * The loaded values of metrics by window and detail level. Only the missing values are requested, one request at a time
 * for each level.
 */
export class MetricValueCache {
    /**
     * @param {(window: string, detail: string, ids: string[], signal: AbortSignal, from: string|null) => Promise<{dates: string[], series: Object<string, object>}>} fetchValues
     *  Loads the values of metrics from /chart-data.
     */
    constructor(fetchValues) {
        this.fetchValues = fetchValues;
        /**
         * The values of each level, `from` is the first date of the loaded values, null for all dates, see {@link load}.
         * @type {Object<string, {window: string, detail: string, from: string|null, data: MetricData|null, pending: boolean, failed: Set<string>}>}
         */
        this.entries = {};
        /** The pending requests, see {@link abort}. */
        this.requests = new Set();
    }

    /**
     * Aborts the pending requests, e.g. when the chart dialog is closed. Their values aren't failed, they're
     * requested again, when they're needed.
     */
    abort() {
        this.requests.forEach(controller => controller.abort());
        this.requests.clear();
    }

    /** @return {Promise<object>} The values of /chart-data, a request, which {@link abort} can abort. */
    async fetchAbortable(window, detail, ids, from) {
        const controller = new AbortController();
        this.requests.add(controller);
        try {
            return await this.fetchValues(window, detail, ids, controller.signal, from);
        } finally {
            this.requests.delete(controller);
        }
    }

    /** @return {MetricData|null} The loaded values of the window and the detail level, null before they're loaded. */
    data(window, detail) {
        return this.entries[window + "/" + detail]?.data ?? null;
    }

    /** @return {boolean} If values are loading. */
    get isLoading() {
        return Object.values(this.entries).some(entry => entry.pending);
    }

    /**
     * Loads the values, which aren't loaded yet, of a window and a detail level. The values of other metrics of the
     * level are kept, if the loaded values have the same dates. Values, which failed to load, are requested again
     * after the current task, e.g. when the charts are drawn the next time.
     *
     * @param {string[]} ids The IDs of the metrics and of their averages, see {@link metricDataId}.
     * @param {string|null} [from] The first shown date, ISO, null for all dates. The server loads some values only
     *  from then on, e.g. the downloads of a release. Loaded values with the same or an earlier start are used, an
     *  earlier start loads the values of the level again.
     * @return {Promise<{data: object, requested: string[]}>|null} The response and the requested IDs, null if no values
     *  are requested: all values are loaded, or values of the level are loading. The missing values are requested
     *  again, when the current request is done.
     */
    load(window, detail, ids, from = null) {
        const entry = this.entries[window + "/" + detail] ??= {window, detail, from, data: null, pending: false, failed: new Set()};
        if (!entry.pending && !coversStart(entry.from, from)) {
            entry.data = null;
            entry.from = from;
        }
        const requested = [...new Set(ids)].filter(id => !entry.failed.has(id) && !(entry.data?.series[id] || entry.data?.missing.has(id)));
        if (requested.length === 0 || entry.pending) {
            return null;
        }
        entry.pending = true;
        return this.fetchAbortable(window, detail, requested, entry.from)
            .then(data => {
                const series = data.dates.join() === entry.data?.dates.join() ? {...entry.data.series, ...data.series} : data.series;
                const missing = new Set([...(entry.data?.missing ?? []), ...requested.filter(id => !data.series[id])]);
                entry.data = {dates: data.dates, series, missing};
                return {data, requested};
            })
            .catch(error => {
                // an aborted request isn't a failure, its values are requested again, when they're needed
                if (!isAbortError(error)) {
                    requested.forEach(id => entry.failed.add(id));
                    setTimeout(() => requested.forEach(id => entry.failed.delete(id)));
                }
                throw error;
            })
            .finally(() => entry.pending = false);
    }

    /**
     * Loads the loaded values of all levels again, e.g. to show new sales. The loaded values are kept until the new
     * values are loaded, a level whose values are loading and a level whose request fails keep their values.
     *
     * @return {Promise<{changed: boolean, responses: {data: object, requested: string[]}[]}>} If any values or dates
     *  changed, and the responses with the requested IDs.
     */
    async refresh() {
        const entries = Object.values(this.entries).filter(entry => !entry.pending && entry.data && Object.keys(entry.data.series).length > 0);
        const results = await Promise.all(entries.map(async entry => {
            const previous = entry.data;
            const requested = Object.keys(previous.series);
            entry.pending = true;
            try {
                const data = await this.fetchAbortable(entry.window, entry.detail, requested, entry.from);
                const missing = new Set([...previous.missing, ...requested.filter(id => !data.series[id])]);
                entry.data = {dates: data.dates, series: data.series, missing};
                const changed = data.dates.join() !== previous.dates.join()
                    || requested.some(id => JSON.stringify(data.series[id]) !== JSON.stringify(previous.series[id]));
                return {changed, response: {data, requested}};
            } catch (error) {
                if (!isAbortError(error)) {
                    console.error("Loading the values of metrics again failed", error);
                }
                return {changed: false, response: null};
            } finally {
                entry.pending = false;
            }
        }));
        return {changed: results.some(result => result.changed), responses: results.map(result => result.response).filter(Boolean)};
    }
}

/** @return {boolean} If the error is of an aborted request, see {@link MetricValueCache#abort}. */
export function isAbortError(error) {
    return error?.name === "AbortError";
}

/**
 * @param {string|null} loaded The first date of loaded values, ISO, null for all dates.
 * @param {string|null} shown The first shown date, null for all dates.
 * @return {boolean} If the loaded values cover the shown dates, e.g. the values of the whole year for a month.
 */
export function coversStart(loaded, shown) {
    return loaded === null || (shown !== null && shown >= loaded);
}
