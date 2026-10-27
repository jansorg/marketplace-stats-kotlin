/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

/**
 * Requests of the charts to the server, e.g. of the values of metrics.
 */

/**
 * @param {string} path The path of an endpoint of the server, e.g. `/chart-data`.
 * @return {URL} The address of the endpoint with the plugin of the page, without a plugin for the summary of all plugins.
 */
export function pageEndpoint(path) {
    const url = new URL(path, window.location.href);
    const pluginId = new URLSearchParams(window.location.search).get("pluginId");
    if (pluginId !== null) {
        url.searchParams.set("pluginId", pluginId);
    }
    return url;
}

/** @return {Promise<any>} The JSON of the response, rejected if the response has an error status. */
export async function fetchJson(url) {
    const response = await fetch(url);
    if (!response.ok) {
        throw new Error("HTTP status " + response.status);
    }
    return response.json();
}

/**
 * @param {string} window The window of the values, see WINDOWS in model.js.
 * @param {string} detail The detail level of the values, see DETAILS in model.js.
 * @param {string[]} ids The IDs of the metrics and of their averages, e.g. `sales@avg3`.
 * @return {Promise<{dates: string[], series: Object<string, object>}>} The values of /chart-data.
 */
export function fetchMetricValues(window, detail, ids) {
    const url = pageEndpoint("/chart-data");
    url.searchParams.set("metrics", ids.join(","));
    url.searchParams.set("window", window);
    url.searchParams.set("detail", detail);
    return fetchJson(url);
}

/** @return {Promise<object[]>} The metrics of the page of /chart-metrics, for the menu to add a metric. */
export function fetchMetrics() {
    return fetchJson(pageEndpoint("/chart-metrics"));
}
