/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

/**
 * Requests of the charts to the server, e.g. of the values of metrics.
 */

/**
 * @param {string} path The path of an endpoint of the server, e.g. `/chart-data`.
 * @return {URL} The address of the endpoint with the plugin of the page, `data-plugin-id` of the `<body>` or the
 *  parameter `pluginId` of the address, without a plugin for the summary of all plugins.
 */
export function pageEndpoint(path) {
    const url = new URL(path, window.location.href);
    // e.g. the page of a release has the parameter pId
    const pluginId = document.body.dataset.pluginId || new URLSearchParams(window.location.search).get("pluginId");
    if (pluginId !== null) {
        url.searchParams.set("pluginId", pluginId);
    }
    return url;
}

/**
 * @param {AbortSignal} [signal] Aborts the request, e.g. when the chart dialog is closed.
 * @return {Promise<any>} The JSON of the response, rejected if the response has an error status.
 */
export async function fetchJson(url, signal) {
    const response = await fetch(url, {signal});
    if (!response.ok) {
        throw new Error("HTTP status " + response.status);
    }
    return response.json();
}

/**
 * @param {string} window The window of the values, see WINDOWS in model.js.
 * @param {string} detail The detail level of the values, see DETAILS in model.js.
 * @param {string[]} ids The IDs of the metrics and of their averages, e.g. `sales@avg3`.
 * @param {AbortSignal} [signal] Aborts the request, the server stops loading the values, see cancelOnDisconnect.
 * @return {Promise<{dates: string[], series: Object<string, object>}>} The values of /chart-data.
 */
export function fetchMetricValues(window, detail, ids, signal) {
    const url = pageEndpoint("/chart-data");
    url.searchParams.set("metrics", ids.join(","));
    url.searchParams.set("window", window);
    url.searchParams.set("detail", detail);
    return fetchJson(url, signal);
}

/** @return {Promise<object[]>} The metrics of the page of /chart-metrics, for the menu to add a metric. */
export function fetchMetrics() {
    return fetchJson(pageEndpoint("/chart-metrics"));
}

/**
 * @param {string} family The ID of a family of metrics with parameters, e.g. `release-downloads`.
 * @param {string} parameter The ID of the parameter, e.g. `release`.
 * @return {Promise<{value: string, label: string}[]>} The values of the parameter of /chart-metric-parameters, e.g.
 *  the releases of the plugin.
 */
export function fetchParameterValues(family, parameter) {
    const url = pageEndpoint("/chart-metric-parameters");
    url.searchParams.set("family", family);
    url.searchParams.set("parameter", parameter);
    return fetchJson(url);
}
