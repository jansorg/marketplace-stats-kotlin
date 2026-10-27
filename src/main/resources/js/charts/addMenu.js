/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

/**
 * The menu "+" of the chart dialog, which adds a metric of the page to the chart of its unit.
 */

import {fetchMetrics} from "./endpoints.js";
import {clickOpensSubmenu} from "./menu.js";
import {WINDOW_LABELS, WINDOWS} from "./model.js";

/** The values of a metric in the menu: the values and their moving averages over 1, 3 and 12 months. */
const ADD_MENU_VALUES = [
    [null, "Values", "The values of the selected window"],
    [1, "1-month moving average", "The average of the month before each date"],
    [3, "3-month moving average", "The average of the 3 months before each date"],
    [12, "12-month moving average", "The average of the 12 months before each date, without seasonal changes"],
];

/**
 * Fills the menu to add a metric: a selection of the window of sums and rates, and the metrics of the page, which are
 * loaded from /chart-metrics. A menu, which is already filled, is unchanged.
 *
 * @param {HTMLElement} items The element of the items of the menu.
 * @param {object} options
 * @param {string} options.selectedWindow The selected window, see WINDOWS in model.js.
 * @param {(window: string) => void} options.onWindowChange Called when another window is selected.
 * @param {(metric: object, window: string, average: number|null) => void} options.onAdd Called with a metric of
 *  /chart-metrics, the selected window and the months of a moving average, null for the values.
 * @param {() => void} options.onError Called after the menu was closed, if the metrics couldn't be loaded.
 * @param {() => void} options.close Closes the menu.
 */
export function fillAddMenu(items, {selectedWindow, onWindowChange, onAdd, onError, close}) {
    if (items.childElementCount > 0) {
        return;
    }
    const windowLabel = document.createElement("label");
    windowLabel.className = "chart-add-window";
    const windowSelect = document.createElement("select");
    for (const window of WINDOWS) {
        windowSelect.append(new Option(WINDOW_LABELS[window], window, false, window === selectedWindow));
    }
    windowSelect.addEventListener("change", () => onWindowChange(windowSelect.value));
    windowLabel.append("Window", windowSelect);
    items.append(windowLabel);
    const add = (metric, average) => {
        close();
        onAdd(metric, windowSelect.value, average);
    };
    fetchMetrics()
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
                items.append(addMenuItem(metric, average => add(metric, average)));
            }
        })
        .catch(error => {
            console.error("Loading the metrics failed", error);
            items.replaceChildren();
            close();
            onError();
        });
}

/**
 * @param {{title: string, usesWindow: boolean, description?: string}} metric A metric of /chart-metrics.
 * @param {(average: number|null) => void} add Adds the values or a moving average of the metric.
 * @return {HTMLElement} An item of the menu: a click adds the values of the metric, hovering it shows a submenu with
 *  the values and the moving averages. A tap of a touch screen shows the submenu below the item.
 */
function addMenuItem(metric, add) {
    const item = document.createElement("div");
    item.className = "chart-add-metric";
    const button = document.createElement("button");
    button.type = "button";
    button.setAttribute("role", "menuitem");
    button.setAttribute("aria-haspopup", "menu");
    button.textContent = metric.title;
    // values at a date, e.g. the MRR, don't use the window
    const windowHint = metric.usesWindow ? "Sums or rates of the selected window" : "Values at each date";
    button.title = metric.description ? `${metric.description}\n${windowHint}` : windowHint;
    // A mouse shows the submenu on hover and a click adds the values. A tap of a touch screen or a pen shows and hides
    // the submenu below the item, within the list of the menu: without hover, e.g. on an iPad, the submenu beside
    // the item wasn't usable. The pointer of the last pointerdown, e.g. before the click or the focus of the item.
    let pointerType = null;
    item.addEventListener("pointerdown", event => pointerType = event.pointerType);
    item.addEventListener("keydown", () => pointerType = null);
    button.addEventListener("click", () => {
        if (clickOpensSubmenu(pointerType)) {
            toggleInline();
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
    const closeOthers = () => item.parentElement?.querySelectorAll(".chart-add-open, .chart-add-inline")
        .forEach(open => open !== item && open.classList.remove("chart-add-open", "chart-add-inline"));
    const toggleInline = () => {
        closeOthers();
        item.classList.remove("chart-add-open");
        if (item.classList.toggle("chart-add-inline")) {
            submenu.scrollIntoView({block: "nearest"});
        }
    };
    const show = () => {
        closeOthers();
        if (item.classList.contains("chart-add-inline")) {
            return;
        }
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
    // the focus of a tap, e.g. in Chromium, doesn't show the submenu beside the item
    item.addEventListener("focusin", () => !clickOpensSubmenu(pointerType) && show());
    item.addEventListener("pointerleave", event => event.pointerType === "mouse" && item.classList.remove("chart-add-open"));
    item.addEventListener("focusout", event => {
        if (!item.contains(event.relatedTarget)) {
            item.classList.remove("chart-add-open");
        }
    });
    item.append(button, submenu);
    return item;
}
