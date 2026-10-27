/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

/**
 * The dialog, which shows the charts.
 */

import {createMenu} from "./menu.js";
import {eventMenuItems, MARKER_LABELS, rangeLabel, rangeMenuItems} from "./model.js";

/**
 * @return {number} The maximum height of the menu to add a metric in pixels, a longer menu scrolls. Touch screens use
 *  the height of the window, so that a finger scrolls less.
 */
const addMenuMaxHeight = () => window.matchMedia("(pointer: coarse)").matches ? Infinity : 640;

const SVG_NAMESPACE = "http://www.w3.org/2000/svg";

/** The time, after which a message of the dialog is hidden, in milliseconds. */
const MESSAGE_DURATION = 10_000;

/**
 * The time after a page is shown again, after which the charts are measured once more, in milliseconds. Safari on the
 * iPad restores the size of the window only after it showed the page.
 */
const RESUME_LAYOUT_DELAY = 500;

/** Arrows pointing to the corners to maximize, and to the center to restore. */
const ICON_PATHS = {
    maximize: "M3 8V3h5 M3 3l6 6 M17 8V3h-5 M17 3l-6 6 M3 12v5h5 M3 17l6-6 M17 12v5h-5 M17 17l-6-6",
    restore: "M8 3v5H3 M8 8L2 2 M12 3v5h5 M12 8l6-6 M8 17v-5H3 M8 12l-6 6 M12 17v-5h5 M12 12l6 6",
    // a plus, to add a metric
    add: "M10 4v12 M4 10h12",
    // a trash can, to remove all charts
    clear: "M3.5 5h13 M8 5V3h4v2 M5.5 5l1 12h7l1-12 M8.5 8v6 M11.5 8v6",
};

/** Titles of the kinds of vertical lines in the menu "Events". */
const MARKER_TITLES = {
    release: "Show the major releases of the plugin as vertical lines",
    price: "Show the changes of the annual base price as vertical lines",
    ide: "Show the major releases of IntelliJ IDEA as vertical lines",
};

function icon(name) {
    const svg = document.createElementNS(SVG_NAMESPACE, "svg");
    svg.setAttribute("viewBox", "0 0 20 20");
    svg.setAttribute("aria-hidden", "true");
    svg.classList.add("chart-icon");
    const path = document.createElementNS(SVG_NAMESPACE, "path");
    path.setAttribute("d", ICON_PATHS[name]);
    svg.append(path);
    return svg;
}


function button(text, onClick, className) {
    const result = document.createElement("button");
    result.type = "button";
    result.textContent = text;
    if (className) {
        result.className = className;
    }
    result.addEventListener("click", onClick);
    return result;
}

/**
 * @param {string} label The accessible label and the title, e.g. "Close".
 * @return {HTMLButtonElement} A button with a ×, which removes a chart or closes the dialog.
 */
export function removeButton(label, onClick) {
    const result = button("×", onClick, "chart-remove");
    result.title = label;
    result.setAttribute("aria-label", label);
    return result;
}

export class ChartDialog {
    /**
     * @param {object} callbacks
     * @param {() => void} callbacks.onRemoveAll
     * @param {(combine: boolean) => void} callbacks.onCombineUnitsChange
     * @param {(maximized: boolean) => void} callbacks.onMaximizedChange
     * @param {() => void} callbacks.onResize
     * @param {(kind: string, visible: boolean) => void} callbacks.onMarkerVisibilityChange
     * @param {(id: string, visible: boolean) => void} callbacks.onEventVisibilityChange
     * @param {(kind: "min"|"max", visible: boolean) => void} callbacks.onExtremeVisibilityChange
     * @param {(visible: boolean) => void} callbacks.onIncompleteVisibilityChange
     * @param {(visible: boolean) => void} callbacks.onValueTableVisibilityChange
     * @param {(items: HTMLElement, close: () => void) => void} [callbacks.onAddMenuOpen] Fills the menu to add a metric.
     * @param {(visible: boolean) => void} callbacks.onComparisonLinesVisibilityChange
     * @param {(hidden: boolean) => void} callbacks.onHideAxisValuesChange
     * @param {(logarithmic: boolean) => void} callbacks.onLogScaleChange
     * @param {(kind: string, visible: boolean) => void} callbacks.onOverlayVisibilityChange
     * @param {(range: {from: string, to: string}|null) => void} callbacks.onDateRangeChange
     * @param {string[]} markerKinds The kinds of vertical lines, which are available on this page.
     * @param {{id: string, timestamp: number, label: string}[]} customEvents The custom events of this page, the user
     *  selects the shown events in a menu.
     * @param {boolean} hasLineCharts If the page has columns, which can be shown in line charts.
     */
    constructor({onRemoveAll, onCombineUnitsChange, onMaximizedChange, onResize, onMarkerVisibilityChange, onEventVisibilityChange, onExtremeVisibilityChange, onIncompleteVisibilityChange, onValueTableVisibilityChange, onComparisonLinesVisibilityChange, onHideAxisValuesChange, onLogScaleChange, onOverlayVisibilityChange, onDateRangeChange, onAddMenuOpen}, markerKinds, hasLineCharts, customEvents = []) {
        this.element = document.createElement("dialog");
        this.element.className = "chart-dialog noprint";
        this.element.setAttribute("aria-labelledby", "chart-dialog-title");

        const title = document.createElement("h2");
        title.id = "chart-dialog-title";
        // the name of the plugin of the page, see data-plugin-name of the templates
        const pluginName = document.body.dataset.pluginName;
        title.textContent = pluginName ? `${pluginName} Charts` : "Charts";
        // the complete title, if it's shortened on small screens
        title.title = title.textContent;

        this.combineCheckbox = document.createElement("input");
        this.combineCheckbox.type = "checkbox";
        this.combineCheckbox.addEventListener("change", () => onCombineUnitsChange(this.combineCheckbox.checked));
        this.combineLabel = document.createElement("label");
        this.combineLabel.className = "chart-dialog-combine";
        this.combineLabel.title = "Show two units of the same period in one chart, the second unit on the right y-axis";
        this.combineLabel.append(this.combineCheckbox, " Second y-axis");

        this.maximizeButton = button("", () => onMaximizedChange(!this.isMaximized), "chart-dialog-maximize");

        const header = document.createElement("div");
        header.className = "chart-dialog-header";
        const clearButton = button("", onRemoveAll, "chart-dialog-clear");
        clearButton.append(icon("clear"));
        clearButton.title = "Remove all charts, reset their options and close the dialog";
        clearButton.setAttribute("aria-label", "Remove all charts");

        // a menu to add a metric, its items are added when it's opened
        // the long menu is placed in the window and scrolls, so that it doesn't enlarge the dialog
        const {menu: addMenu, items: addItems} = createMenu("", {fixed: true, alignEnd: true, maxHeight: addMenuMaxHeight});
        addMenu.classList.add("chart-add-menu");
        const addSummary = addMenu.querySelector("summary");
        addSummary.append(icon("add"));
        addSummary.title = "Add a chart of a metric";
        addSummary.setAttribute("aria-label", "Add a chart of a metric");
        addMenu.addEventListener("toggle", () => {
            if (addMenu.open) {
                onAddMenuOpen?.(addItems, () => addMenu.open = false);
            }
        });
        this.addMenu = addMenu;

        header.append(title, this.combineLabel, addMenu, clearButton, this.maximizeButton, removeButton("Close", () => this.close()));

        const hint = document.createElement("p");
        hint.className = "chart-dialog-hint";
        hint.textContent = "Close the dialog and click another column header to add it. Press 'C' to show and hide the charts. "
            + "Drag over a line chart to zoom in. "
            + "Dotted lines, hollow points and striped bars mark incomplete values, which may still change.";

        // the row of the menus of the line charts
        this.markerToggles = document.createElement("div");
        this.markerToggles.className = "chart-marker-toggles";
        this.hasLineCharts = hasLineCharts;
        this.markerToggles.hidden = !hasLineCharts;
        // the vertical lines: the custom events of the configured file, each event is shown separately, and the kinds
        // of vertical lines of the page, with a sample of each line as key
        this.markerCheckboxes = {};
        this.eventCheckboxes = {};
        const eventItems = eventMenuItems(customEvents, markerKinds);
        if (eventItems.length > 0) {
            const {menu: eventMenu, items} = createMenu("Events");
            eventMenu.classList.add("chart-event-menu");
            for (const item of eventItems) {
                if (item.type === "separator") {
                    const separator = document.createElement("div");
                    separator.className = "chart-menu-separator";
                    separator.setAttribute("role", "separator");
                    items.append(separator);
                } else if (item.type === "event") {
                    const {id, timestamp, label} = item.event;
                    const date = new Date(timestamp * 1000).toISOString().substring(0, 10);
                    this.eventCheckboxes[id] = this.addToggle(items, `${date} ${label}`,
                        "Show the event as a vertical line", visible => onEventVisibilityChange(id, visible), "custom");
                } else {
                    const kind = item.kind;
                    this.markerCheckboxes[kind] = this.addToggle(items, MARKER_LABELS[kind] ?? kind,
                        MARKER_TITLES[kind] ?? "Show the vertical lines", visible => onMarkerVisibilityChange(kind, visible), kind);
                }
            }
            this.markerToggles.append(eventMenu);
        }

        // options of the line charts
        const {menu: viewMenu, items: viewOptions} = createMenu("View");
        viewMenu.classList.add("chart-view-menu");
        this.extremeCheckboxes = {
            min: this.addToggle(viewOptions, "Minimum", "Mark the lowest value of each line, incomplete values are ignored",
                visible => onExtremeVisibilityChange("min", visible)),
            max: this.addToggle(viewOptions, "Maximum", "Mark the highest value of each line, incomplete values are ignored",
                visible => onExtremeVisibilityChange("max", visible)),
        };
        this.incompleteCheckbox = this.addToggle(viewOptions, "Incomplete values",
            "Show the values, which may still change, e.g. of the current month", onIncompleteVisibilityChange);
        this.valueTableCheckbox = this.addToggle(viewOptions, "Value table",
            "Show a table with the values at the cursor and one month and one year before and after it instead of the legend",
            onValueTableVisibilityChange);
        this.hideAxisValuesCheckbox = this.addToggle(viewOptions, "Hide axis values",
            "Hide the values of the y-axes, e.g. to share an image of a trend without its numbers", onHideAxisValuesChange);
        this.logScaleCheckbox = this.addToggle(viewOptions, "Logarithmic y-axis",
            "Show the values on a logarithmic y-axis, e.g. to compare lines of very different values. Values of 0 and below aren't drawn",
            onLogScaleChange);
        this.comparisonLinesCheckbox = this.addToggle(viewOptions, "Comparison lines",
            "Mark the dates one month and one year before and after the cursor with vertical lines",
            onComparisonLinesVisibilityChange);

        // trends, which are only added to suitable lines, the moving averages are lines of the menu "Add"
        const trendHeading = document.createElement("span");
        trendHeading.className = "chart-menu-heading";
        trendHeading.textContent = "Trend";
        viewOptions.append(trendHeading);
        this.overlayTitles = {};
        this.overlayCheckboxes = Object.fromEntries([
            ["previousYear", "Previous year", "The values of the previous year as a faint line, e.g. to recognize seasonal changes."],
        ].map(([kind, text, title]) => {
            this.overlayTitles[kind] = {text, title};
            return [kind, this.addToggle(viewOptions, text, title, visible => onOverlayVisibilityChange(kind, visible))];
        }));
        this.applicableOverlays = [];
        this.markerToggles.append(viewMenu);

        // the shown dates of the line charts: all, a year or a zoomed range
        const {menu: rangeMenu, items: rangeItems} = createMenu("Range");
        rangeMenu.classList.add("chart-range-menu");
        this.rangeMenu = rangeMenu;
        this.rangeItems = rangeItems;
        this.onDateRangeChange = onDateRangeChange;
        // after the events, before the view options
        viewMenu.before(rangeMenu);

        // the messages overlay the charts at the top of the visible part of the dialog, also when it's scrolled
        const messageAnchor = document.createElement("div");
        messageAnchor.className = "chart-dialog-message-anchor";
        this.message = document.createElement("div");
        this.message.className = "chart-dialog-message";
        this.message.setAttribute("role", "status");
        this.message.hidden = true;
        this.messageText = document.createElement("p");
        this.message.append(this.messageText, removeButton("Close the message", () => this.showMessage(null)));
        messageAnchor.append(this.message);
        this.messageTimer = null;
        this.isMessagePersistent = false;
        // the message stays, while the mouse is on it or it has the focus, e.g. to select its text
        this.message.addEventListener("pointerenter", () => clearTimeout(this.messageTimer));
        this.message.addEventListener("focusin", () => clearTimeout(this.messageTimer));
        this.message.addEventListener("pointerleave", () => this.hideMessageLater());
        this.message.addEventListener("focusout", event => {
            if (!this.message.contains(event.relatedTarget)) {
                this.hideMessageLater();
            }
        });

        this.panels = document.createElement("div");
        this.panels.className = "chart-panels";

        this.element.append(header, hint, this.markerToggles, messageAnchor, this.panels);
        this.setMaximized(false);
        // a click on the backdrop closes the dialog, a click outside of an open menu closes the menu.
        // The target of clicks on the backdrop and on the padding of the dialog is the dialog, only clicks outside of
        // its bounds are on the backdrop.
        this.element.addEventListener("click", event => {
            if (event.target === this.element && !this.contains(event.clientX, event.clientY)) {
                this.close();
            }
            for (const menu of this.element.querySelectorAll("details[open]")) {
                if (!menu.contains(event.target)) {
                    menu.open = false;
                }
            }
        });
        document.body.append(this.element);
        this.followSize(onResize);
    }

    /**
     * The charts follow the size of the dialog. The resize event of the window isn't enough: on tablets, it's fired
     * before the layout of the rotated screen is complete, and the toolbars of mobile browsers change the size of the
     * dialog without it. A page, which is shown again, e.g. after switching apps, is measured again after its layout,
     * and once more after Safari on the iPad restored the size of the window, which it changes for the snapshots of the
     * app switcher.
     */
    followSize(onResize) {
        let lastSize = null;
        const resize = force => {
            if (!this.isOpen) {
                return;
            }
            const size = `${this.element.clientWidth}x${this.element.clientHeight}`;
            if (force || size !== lastSize) {
                lastSize = size;
                onResize();
            }
        };
        // called after the layout, also for hidden pages
        new ResizeObserver(() => resize(false)).observe(this.element);
        // measured after the layout of the page, which is shown again, and after the window got its size again
        const resizeShownPage = () => {
            requestAnimationFrame(() => resize(true));
            setTimeout(() => resize(false), RESUME_LAYOUT_DELAY);
        };
        window.addEventListener("pageshow", resizeShownPage);
        document.addEventListener("visibilitychange", () => {
            if (document.visibilityState === "visible") {
                resizeShownPage();
            }
        });
        // the visible part of the window changes, e.g. with the toolbars of Safari, also without a change of the dialog
        window.visualViewport?.addEventListener("resize", () => requestAnimationFrame(() => resize(false)));
    }

    /** @return {boolean} If the position of the viewport is within the bounds of the dialog. */
    contains(x, y) {
        const bounds = this.element.getBoundingClientRect();
        return x >= bounds.left && x <= bounds.right && y >= bounds.top && y <= bounds.bottom;
    }

    get isOpen() {
        return this.element.open;
    }

    get isMaximized() {
        return this.element.classList.contains("maximized");
    }

    open() {
        if (!this.isOpen) {
            this.element.showModal();
            this.fixPage();
        }
    }

    /**
     * Safari and the other browsers of iPadOS scroll the page behind a modal dialog with touch gestures on the parts of
     * the dialog, which can't be scrolled, even with `overflow: hidden` on the page. A fixed page can't be scrolled.
     * Its scroll position is restored when the dialog is closed, also with the Escape key.
     */
    fixPage() {
        const scrollY = window.scrollY;
        const style = document.body.style;
        style.position = "fixed";
        style.top = `-${scrollY}px`;
        style.left = "0";
        style.right = "0";
        this.element.addEventListener("close", () => {
            style.position = "";
            style.top = "";
            style.left = "";
            style.right = "";
            window.scrollTo(0, scrollY);
        }, {once: true});
    }

    close() {
        this.element.close();
    }

    /**
     * Shows a message above the charts, which is hidden after {@link MESSAGE_DURATION}, or hides the message.
     *
     * @param {string|null} text The message, null or empty to hide the message.
     * @param {{persistent?: boolean}} [options] A persistent message stays until it's replaced or closed, e.g. while
     *  values are loading or with an address, which the user copies.
     */
    showMessage(text, {persistent = false} = {}) {
        clearTimeout(this.messageTimer);
        this.messageText.textContent = text ?? "";
        this.message.hidden = !text;
        this.isMessagePersistent = persistent;
        this.hideMessageLater();
    }

    /** @return {string|null} The shown message, null without a message. */
    get shownMessage() {
        return this.message.hidden ? null : this.messageText.textContent;
    }

    hideMessageLater() {
        clearTimeout(this.messageTimer);
        if (!this.message.hidden && !this.isMessagePersistent) {
            this.messageTimer = setTimeout(() => this.showMessage(null), MESSAGE_DURATION);
        }
    }

    setMaximized(maximized) {
        this.element.classList.toggle("maximized", maximized);
        const title = maximized ? "Restore" : "Maximize";
        this.maximizeButton.replaceChildren(icon(maximized ? "restore" : "maximize"));
        this.maximizeButton.title = title;
        this.maximizeButton.setAttribute("aria-label", title);
    }

    /**
     * @param {number[]} years The years with values of the shown line charts, the latest year first.
     * @param {{from: string, to: string}|null} range The shown dates, null for all dates.
     */
    setDateRange(years, range) {
        const label = rangeLabel(range);
        // small screens only show the range, see main.css
        const prefix = document.createElement("span");
        prefix.className = "chart-range-prefix";
        prefix.textContent = "Range: ";
        const summary = this.rangeMenu.querySelector("summary");
        summary.replaceChildren(prefix, label);
        summary.title = "Range: " + label;
        // a limited range is highlighted, e.g. the dates of a table of a few days, so that it isn't missed
        this.rangeMenu.classList.toggle("chart-menu-modified", range !== null);
        this.rangeItems.replaceChildren(...rangeMenuItems(years, range).map(item => {
            if (item.heading) {
                const heading = document.createElement("div");
                heading.className = "chart-menu-heading";
                heading.textContent = item.heading;
                return heading;
            }
            const button = document.createElement("button");
            button.type = "button";
            button.textContent = item.label;
            button.setAttribute("role", "menuitemradio");
            button.setAttribute("aria-checked", String(item.label === label));
            button.addEventListener("click", () => {
                this.rangeMenu.open = false;
                this.onDateRangeChange(item.range);
            });
            return button;
        }));
    }

    /**
     * @param {string} [sampleKind] The kind of vertical line, whose sample is shown before the text.
     * @return {HTMLInputElement} The checkbox of a new option of the line charts.
     */
    addToggle(container, text, title, onChange, sampleKind) {
        const checkbox = document.createElement("input");
        checkbox.type = "checkbox";
        checkbox.addEventListener("change", () => onChange(checkbox.checked));
        const label = document.createElement("label");
        label.title = title;
        label.setAttribute("role", "menuitemcheckbox");
        label.append(checkbox);
        if (sampleKind) {
            const sample = document.createElement("span");
            sample.className = "chart-marker-sample chart-marker-" + sampleKind;
            label.append(sample);
        }
        label.append(text);
        container.append(label);
        return checkbox;
    }

    /**
     * Trends, which don't apply to any of the shown series, are dimmed.
     *
     * @param {string[]} kinds The trends, which apply to at least one series of the shown line charts.
     */
    setApplicableOverlays(kinds) {
        this.applicableOverlays = kinds;
        for (const [kind, checkbox] of Object.entries(this.overlayCheckboxes)) {
            const label = checkbox.parentElement;
            const isApplicable = kinds.includes(kind);
            label.classList.toggle("chart-option-unavailable", !isApplicable);
            label.title = this.overlayTitles[kind].title + (isApplicable ? "" : "\nNot available for the shown charts.");
        }
    }

    isOverlayApplicable(kind) {
        return this.applicableOverlays.includes(kind);
    }

    /** @return {string} Why a trend isn't shown. */
    overlayHint(kind) {
        const {text, title} = this.overlayTitles[kind];
        return `${text}: not available for the shown charts. ${title}`;
    }

    /** @param {string[]} shownKinds */
    setShownOverlays(shownKinds) {
        for (const [kind, checkbox] of Object.entries(this.overlayCheckboxes)) {
            checkbox.checked = shownKinds.includes(kind);
        }
        this.shownOverlays = shownKinds;
    }

    setShowValueTable(visible) {
        this.valueTableCheckbox.checked = visible;
    }

    setHideAxisValues(hidden) {
        this.hideAxisValuesCheckbox.checked = hidden;
    }

    setLogScale(logarithmic) {
        this.logScaleCheckbox.checked = logarithmic;
    }

    setShowComparisonLines(visible) {
        this.comparisonLinesCheckbox.checked = visible;
    }

    setShowIncomplete(visible) {
        this.incompleteCheckbox.checked = visible;
    }

    /** @param {("min"|"max")[]} shownKinds */
    setShownExtremes(shownKinds) {
        for (const [kind, checkbox] of Object.entries(this.extremeCheckboxes)) {
            checkbox.checked = shownKinds.includes(kind);
        }
    }

    setShownMarkers(shownKinds) {
        for (const [kind, checkbox] of Object.entries(this.markerCheckboxes)) {
            checkbox.checked = shownKinds.includes(kind);
        }
    }

    /** @param {string[]} shownIds The IDs of the shown custom events. */
    setShownEvents(shownIds) {
        for (const [id, checkbox] of Object.entries(this.eventCheckboxes)) {
            checkbox.checked = shownIds.includes(id);
        }
    }

    /**
     * @param {boolean} visible The option is only shown if it would combine charts.
     */
    /**
     * Shows the menus of the line charts, e.g. "Range" and "View", also on pages without columns for line charts, if
     * line charts are shown, e.g. of a metric of the menu "Add" or of the button of a row of a table.
     *
     * @param {boolean} shown If line charts are shown.
     */
    setLineChartsShown(shown) {
        this.markerToggles.hidden = !(this.hasLineCharts || shown);
    }

    setCombineUnits(combine, visible) {
        this.combineCheckbox.checked = combine;
        this.combineLabel.hidden = !visible;
    }

    /** @return {HTMLElement} A new, empty figure for a chart. */
    addPanel(className) {
        const panel = document.createElement("figure");
        panel.className = className;
        this.panels.append(panel);
        return panel;
    }

    clearPanels() {
        this.panels.replaceChildren();
        this.markerToggles.querySelector(".chart-toolbar")?.remove();
    }

    /**
     * Shows the toolbar of the first chart at the end of the row of the options, right-aligned.
     *
     * @return {boolean} If the toolbar is shown, the row is hidden on pages without line charts.
     */
    showToolbar(toolbar) {
        if (this.markerToggles.hidden) {
            return false;
        }
        this.markerToggles.append(toolbar);
        return true;
    }

    showEmptyHint() {
        const empty = document.createElement("p");
        empty.className = "chart-empty-hint";
        empty.textContent = "Use + to add a chart, or click the header of a column of a table.";
        this.panels.append(empty);
    }

    /** @return {number} The width of the charts in pixels. */
    get chartWidth() {
        return Math.max(300, this.panels.clientWidth);
    }

    /**
     * Maximized line charts fill the height of the dialog. The heights are corrected after rendering by the
     * measured space, because the heights of the header and the legends are only estimated by {@link chartHeight}.
     *
     * @param {uPlot[]} plots The line charts of the dialog.
     */
    fitChartHeights(plots) {
        const lastPanel = this.panels.lastElementChild;
        if (!this.isMaximized || plots.length === 0 || lastPanel === null) {
            return;
        }
        const dialogBounds = this.element.getBoundingClientRect();
        const contentBottom = lastPanel.getBoundingClientRect().bottom - dialogBounds.top + this.element.scrollTop
            + Number.parseFloat(getComputedStyle(this.element).paddingBottom);
        // positive if there's space left, negative if the charts are too high
        const freeSpace = Math.floor((this.element.clientHeight - contentBottom) / plots.length);
        if (freeSpace !== 0) {
            plots.forEach(plot => plot.setSize({width: plot.width, height: Math.max(200, plot.height + freeSpace)}));
        }
    }

    /**
     * @return {number} The height of each line chart. Maximized charts share the height of the window.
     */
    chartHeight(chartCount) {
        if (!this.isMaximized) {
            return 300;
        }
        // the height of the maximized dialog is the height of the window, which Safari may report wrongly for a moment,
        // after it returned from another app. The space for the header and the legends is an estimate.
        const available = (this.element.clientHeight || window.innerHeight) - 140 - chartCount * 90;
        return Math.max(300, Math.floor(available / Math.max(1, chartCount)));
    }
}
