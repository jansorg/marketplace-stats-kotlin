/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

/**
 * The buttons of the toolbar of a line chart, which select its detail level and its style.
 */

import {createMenu} from "./menu.js";
import {CHART_STYLES, DETAILS} from "./model.js";

const STYLE_LABELS = {lines: "Lines", bars: "Bars"};
const DETAIL_LABELS = {year: "Year", month: "Month", week: "Week", day: "Day"};
const DETAIL_HINT = "The dates of the values: the end of each year, of each month or of each week, or each day, and today.";

/**
 * @param {string} className The CSS classes of the group.
 * @param {string} label The accessible label of the group.
 * @param {{value: string, text: string}[]} options
 * @param {string} selected The value of the pressed button.
 * @param {(value: string) => void} onSelect Called with the value of a clicked button.
 * @return {HTMLElement} A group of buttons, of which one is pressed.
 */
function buttonGroup(className, label, options, selected, onSelect) {
    const group = document.createElement("div");
    group.className = className;
    group.setAttribute("role", "group");
    group.setAttribute("aria-label", label);
    for (const {value, text} of options) {
        const button = document.createElement("button");
        button.type = "button";
        button.textContent = text;
        button.setAttribute("aria-pressed", String(value === selected));
        button.addEventListener("click", () => onSelect(value));
        group.append(button);
    }
    return group;
}

/**
 * @param {string} detail The detail level of the chart, see DETAILS in model.js.
 * @param {(detail: string) => void} onSelect Called with the selected detail level.
 * @return {HTMLElement} Buttons to show the values of a chart of metrics at the end of each year, month or week, or of
 *  each day.
 */
export function detailSwitch(detail, onSelect) {
    const group = buttonGroup("chart-button-group", "Values per", DETAILS.map(value => ({value, text: DETAIL_LABELS[value]})), detail, onSelect);
    group.title = DETAIL_HINT;
    return group;
}

/**
 * @param {"lines"|"bars"} style The style of the chart.
 * @param {(style: "lines"|"bars") => void} onSelect Called with the selected style.
 * @return {HTMLElement} Buttons to switch a line chart between lines and bars.
 */
export function styleSwitch(style, onSelect) {
    return buttonGroup("chart-button-group chart-style-switch", "Chart style", CHART_STYLES.map(value => ({value, text: STYLE_LABELS[value]})), style, onSelect);
}

/**
 * On small screens, a group of buttons, of which one is selected, e.g. the levels Year, Month, Week and Day, is a
 * dropdown menu with the selected button as its label, which opens on hover or with a tap. See main.css.
 *
 * @param {HTMLElement} group The buttons with `aria-pressed`, e.g. of {@link detailSwitch}.
 * @return {HTMLElement} The buttons and the menu, CSS shows one of them.
 */
export function compactSwitch(group) {
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
