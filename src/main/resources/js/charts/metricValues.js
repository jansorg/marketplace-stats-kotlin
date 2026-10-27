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
 *  measure?: string|null, row?: string, style?: string}} series A series of a metric column, of the menu to add
 *  metrics or of the stored setup. Sums and rates have a window, e.g. the trials of each month and of each day are two
 *  series.
 * @param {string} color
 * @return {object} The series of the metric, its values are loaded when it's shown, see ChartController.metricChartSeries.
 */
export function metricSeries({metric, window, usesWindow, average, label, unit, measure, row, style}, color) {
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
export function metricDataId(series) {
    return series.average ? `${series.metric}@avg${series.average}` : series.metric;
}

/** @return {import("./storage.js").ChartEntry} The stored entry of a series of a metric, see {@link metricSeries}. */
export function metricEntry(s) {
    return {type: "metric", metric: s.metric, ...(s.window ? {window: s.window} : {}), usesWindow: s.usesWindow, ...(s.average ? {average: s.average} : {}), label: s.label, unit: s.unit,
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
     * @param {(window: string, detail: string, ids: string[]) => Promise<{dates: string[], series: Object<string, object>}>} fetchValues
     *  Loads the values of metrics from /chart-data.
     */
    constructor(fetchValues) {
        this.fetchValues = fetchValues;
        /** @type {Object<string, {data: MetricData|null, pending: boolean, failed: Set<string>}>} */
        this.entries = {};
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
     * @return {Promise<{data: object, requested: string[]}>|null} The response and the requested IDs, null if no values
     *  are requested: all values are loaded, or values of the level are loading. The missing values are requested
     *  again, when the current request is done.
     */
    load(window, detail, ids) {
        const entry = this.entries[window + "/" + detail] ??= {data: null, pending: false, failed: new Set()};
        const requested = [...new Set(ids)].filter(id => !entry.failed.has(id) && !(entry.data?.series[id] || entry.data?.missing.has(id)));
        if (requested.length === 0 || entry.pending) {
            return null;
        }
        entry.pending = true;
        return this.fetchValues(window, detail, requested)
            .then(data => {
                const series = data.dates.join() === entry.data?.dates.join() ? {...entry.data.series, ...data.series} : data.series;
                const missing = new Set([...(entry.data?.missing ?? []), ...requested.filter(id => !data.series[id])]);
                entry.data = {dates: data.dates, series, missing};
                return {data, requested};
            })
            .catch(error => {
                requested.forEach(id => entry.failed.add(id));
                setTimeout(() => requested.forEach(id => entry.failed.delete(id)));
                throw error;
            })
            .finally(() => entry.pending = false);
    }
}
