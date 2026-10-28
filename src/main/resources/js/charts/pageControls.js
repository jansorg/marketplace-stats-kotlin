/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

/**
 * The elements of the page, which open the charts: the column headers of the tables, the logo of the plugin, the hints
 * of the shortcut and the key C.
 */

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

/** Enter and Space activate an element with the role button. */
function onActivationKey(element, activate) {
    element.addEventListener("keydown", event => {
        if (event.key === "Enter" || event.key === " ") {
            event.preventDefault();
            activate();
        }
    });
}

/**
 * The key C shows and hides the charts, unless a text field has the focus.
 *
 * @param {() => void} toggle Shows or hides the charts.
 */
export function enableShortcut(toggle) {
    document.addEventListener("keydown", event => {
        if (event.key.toLowerCase() !== "c" || event.ctrlKey || event.metaKey || event.altKey || event.repeat || isTextInput(event.target)) {
            return;
        }
        event.preventDefault();
        toggle();
    });
}

/**
 * A click on a hint of the shortcut, e.g. "Press C to show the charts", opens the charts.
 */
export function makeHintsClickable(open) {
    for (const hint of document.querySelectorAll("[data-show-charts]")) {
        hint.addEventListener("click", open);
    }
}

/**
 * A click on the logo of the plugin opens the charts.
 */
export function makeLogoClickable(open) {
    const logo = document.querySelector("img.plugin-icon");
    if (logo === null) {
        return;
    }
    logo.classList.add("chart-opener");
    logo.tabIndex = 0;
    logo.setAttribute("role", "button");
    logo.title = "Show charts (C)";
    logo.setAttribute("aria-keyshortcuts", "C");
    logo.addEventListener("click", open);
    onActivationKey(logo, open);
}

/**
 * A click on the header of a column shows it in a chart. In a sortable table, only a click on the label of the header
 * shows the chart, a click on the rest of the header, e.g. the arrow, sorts the table.
 *
 * @param {{header: HTMLElement, type: string}} column A column of {@link chartColumns}.
 * @param {HTMLTableElement} table The table of the column.
 * @param {() => void} show Shows the column in a chart.
 */
export function makeColumnClickable({header, type}, table, show) {
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
    onActivationKey(target, show);
}

/**
 * The buttons next to the names of rows show a metric of the row in a chart, e.g. the discount of an offer, see
 * ChartMetricName. They're hidden without the charts. A click on the name shows the chart, too, the name is marked
 * like the labels of the columns with charts.
 *
 * @param {(metric: {id: string, title: string, unit: string}) => void} show Shows the metric in a chart.
 * @return {boolean} If the page has such buttons.
 */
export function makeRowChartButtons(show) {
    const buttons = [...document.querySelectorAll("button[data-chart-row-metric]")];
    for (const button of buttons) {
        button.hidden = false;
        const {chartRowMetric: id, chartRowTitle: title, chartRowUnit: unit} = button.dataset;
        const showMetric = () => show({id, title, unit});
        button.addEventListener("click", showMetric);
        // the name is in front of the button or, e.g. of a number, after it
        const name = button.classList.contains("chart-row-button-first") ? button.nextElementSibling : button.previousElementSibling;
        if (name?.classList.contains("chart-row-name")) {
            name.classList.add("chartable");
            name.tabIndex = 0;
            name.setAttribute("role", "button");
            name.title = button.title;
            name.addEventListener("click", showMetric);
            onActivationKey(name, showMetric);
        }
    }
    return buttons.length > 0;
}
